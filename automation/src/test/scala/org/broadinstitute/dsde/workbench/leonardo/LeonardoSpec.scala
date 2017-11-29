package org.broadinstitute.dsde.workbench.leonardo

import java.io.File
import java.nio.file.Files
import java.time.Instant

import org.broadinstitute.dsde.firecloud.api.Sam
import org.broadinstitute.dsde.workbench.api.APIException
import org.broadinstitute.dsde.workbench.{ResourceFile, WebBrowserSpec}
import org.broadinstitute.dsde.workbench.config.AuthToken
import org.broadinstitute.dsde.workbench.leonardo.ClusterStatus.ClusterStatus
import org.broadinstitute.dsde.workbench.leonardo.StringValueClass.LabelMap
import org.broadinstitute.dsde.workbench.util.LocalFileUtil
import org.openqa.selenium.WebDriver
import org.scalatest.concurrent.Eventually
import org.scalatest.selenium.WebBrowser
import org.scalatest.time.{Minutes, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers, ParallelTestExecution}

import scala.util.{Random, Try}

class LeonardoSpec extends FreeSpec with Matchers with Eventually with WebBrowserSpec with WebBrowser with ParallelTestExecution with LocalFileUtil with BeforeAndAfterAll {
  implicit val ronAuthToken = AuthToken(LeonardoConfig.Users.ron)

  // simultaneous requests by the same user to create their pet in Sam can cause contention
  // but this is not a realistic production scenario
  // so we avoid this by pre-creating

  override def beforeAll(): Unit = {
    // ensure pet is initialized in test env
    Sam.user.petServiceAccount()
  }

  val project = GoogleProject(LeonardoConfig.Projects.default)
  val sa = GoogleServiceAccount(LeonardoConfig.Leonardo.notebooksServiceAccountEmail)
  val bucket = GcsBucketName("mah-bukkit")
  val incorrectJupyterExtensionUri = "gs://leonardo-swat-test-bucket-do-not-delete"
  // ------------------------------------------------------

  def labelCheck(seen: LabelMap,
                 requestedLabels: LabelMap,
                 clusterName: ClusterName,
                 googleProject: GoogleProject,
                 gcsBucketName: GcsBucketName): Unit = {

    // we don't actually know the SA because it's the pet
    // set a dummy here and then remove it from the comparison

    val dummyPetSa = WorkbenchUserServiceAccountEmail("dummy")
    val expected = requestedLabels ++ DefaultLabels(clusterName, googleProject, gcsBucketName, dummyPetSa, None).toMap - "serviceAccount"

    (seen - "serviceAccount" - "notebookExtension") shouldBe (expected - "serviceAccount" - "notebookExtension")

  }

  def clusterCheck(cluster: Cluster,
                   requestedLabels: Map[String, String],
                   expectedName: ClusterName,
                   expectedStatuses: Iterable[ClusterStatus]): Cluster = {

    expectedStatuses should contain (cluster.status)
    cluster.googleProject shouldBe project
    cluster.clusterName shouldBe expectedName
    cluster.googleBucket shouldBe bucket
    labelCheck(cluster.labels, requestedLabels, expectedName, project, bucket)

    cluster
  }

  // creates a cluster and checks to see that it reaches the Running state
  def createAndMonitor(googleProject: GoogleProject, clusterName: ClusterName, clusterRequest: ClusterRequest, clusterStatus:ClusterStatus = ClusterStatus.Running): Cluster = {
    val cluster = Leonardo.cluster.create(googleProject, clusterName, clusterRequest)
    clusterCheck(cluster, clusterRequest.labels, clusterName, Seq(ClusterStatus.Creating))

    // verify with get()
    clusterCheck(Leonardo.cluster.get(googleProject, clusterName), clusterRequest.labels, clusterName, Seq(ClusterStatus.Creating))

    // wait for "Running" or error (fail fast)
    val actualCluster = eventually {
      clusterCheck(Leonardo.cluster.get(googleProject, clusterName), clusterRequest.labels, clusterName, Seq(ClusterStatus.Running, ClusterStatus.Error))
    } (PatienceConfig(timeout = scaled(Span(8, Minutes)), interval = scaled(Span(20, Seconds))))

    // now check the cluster is in expected status
    actualCluster.status shouldBe clusterStatus
    actualCluster
  }

  // deletes a cluster and checks to see that it reaches the Deleted state
  def deleteAndMonitor(googleProject: GoogleProject, clusterName: ClusterName): Unit = {
    try {
      Leonardo.cluster.delete(googleProject, clusterName) shouldBe
        "The request has been accepted for processing, but the processing has not been completed."
    } catch {
      // OK if cluster not found / already deleted
      case ae: APIException if ae.message.contains("\"statusCode\":404") => ()
      case e: Exception => throw e
    }

    // wait until not found or in "Deleted" state
    eventually {
      val statusOpt = Leonardo.cluster.listIncludingDeleted().find(_.clusterName == clusterName).map(_.status)
      statusOpt getOrElse ClusterStatus.Deleted shouldBe ClusterStatus.Deleted
    } (PatienceConfig(timeout = scaled(Span(8, Minutes)), interval = scaled(Span(20, Seconds))))
  }


  // TODO: show diffs as screenshot or other test output?
  def compareFilesExcludingIPs(left: File, right: File): Unit = {

    def linesWithoutIPs(file: File) = {
      import scala.collection.JavaConverters._
      Files.readAllLines(left.toPath).asScala map { _.replaceAll("(\\d+.){3}\\d+", "<IP>") }
    }

    linesWithoutIPs(left) shouldEqual linesWithoutIPs(right)
  }

  // ------------------------------------------------------

  def withNewCluster[T](googleProject: GoogleProject)(testCode: Cluster => T): T = {
    val name = ClusterName(s"automation-test-a${Random.alphanumeric.take(10).mkString.toLowerCase}z")
    val request = ClusterRequest(bucket, Map("foo" -> Random.alphanumeric.take(10).mkString))

    val testResult: Try[T] = Try {
      val cluster = createAndMonitor(googleProject, name, request, ClusterStatus.Running)
      testCode(cluster)
    }

    // delete before checking testCode status, which may throw
    deleteAndMonitor(googleProject, name)
    testResult.get
  }


  def withNewErroredCluster[T](googleProject: GoogleProject)(testCode: Cluster => T): T = {
    val name = ClusterName(s"automation-test-a${Random.alphanumeric.take(10).mkString.toLowerCase}z")
    val request = ClusterRequest(bucket, Map("foo" -> Random.alphanumeric.take(10).mkString), Some(incorrectJupyterExtensionUri))
    val testResult: Try[T] = Try {
      val cluster = createAndMonitor(googleProject, name, request, ClusterStatus.Error)
      testCode(cluster)
    }

    // delete before checking testCode status, which may throw
    deleteAndMonitor(googleProject, name)
    testResult.get
  }
  // create a new cluster and wait sufficient time for the jupyter server to be ready
  
  def withReadyCluster[T](googleProject: GoogleProject)(testCode: Cluster => T): T = {
    withNewCluster(googleProject) { cluster =>

      // GAWB-2797: Leo might not be ready to proxy yet
      import concurrent.duration._
      Thread sleep 15.seconds.toMillis

      testCode(cluster)
    }
  }

  def withNotebooksListPage[T](cluster: Cluster)(testCode: NotebooksListPage => T)(implicit webDriver: WebDriver): T = {
    val notebooksListPage = Leonardo.notebooks.get(cluster.googleProject, cluster.clusterName)
    testCode(notebooksListPage.open)
  }

  def withNotebookUpload[T](cluster: Cluster, file: File)(testCode: NotebookPage => T)(implicit webDriver: WebDriver): T = {
    withNotebooksListPage(cluster) { notebooksListPage =>
      notebooksListPage.upload(file)

      val notebookPage = notebooksListPage.openNotebook(file)
      testCode(notebookPage)
    }
  }

  // ------------------------------------------------------

  "Leonardo" - {
    "should ping" in {
      Leonardo.test.ping() shouldBe "OK"
    }

    "should create, monitor, and delete a cluster" in {
      withNewCluster(project) { _ =>
        // no-op; just verify that it launches
      }
    }

    "should error on cluster create and delete the cluster" in {
      withNewErroredCluster(project) { _ =>
        // no-op; just verify that it launches
      }
    }
    "should open the notebooks list page" in withWebDriver { implicit driver =>
      withReadyCluster(project) { cluster =>
        withNotebooksListPage(cluster) { _ =>
          // no-op; just verify that it opens
        }
      }
    }

    "should upload a notebook to Jupyter" in withWebDriver { implicit driver =>
      val file = ResourceFile("diff-tests/import-hail.ipynb")

      withReadyCluster(project) { cluster =>
        withNotebookUpload(cluster, file) { _ =>
          // no-op; just verify that it uploads
        }
      }
    }

    val fileName: String = "import-hail.ipynb"
    val upFile = ResourceFile(s"diff-tests/$fileName")
    val downFile = ResourceFile.downloadsFile(fileName)
    val downloadsDestination = downFile.getParent

    "should verify notebook execution" in withWebDriver(downloadsDestination) { implicit driver =>

      withReadyCluster(project) { cluster =>
        withNotebookUpload(cluster, upFile) { notebook =>
          notebook.runAllCells()
          notebook.download()
        }
      }




      // move the file to a unique location so it won't interfere with other tests
      val uniqueDownFile = ResourceFile.downloadsFile(s"import-hail-${Instant.now().toString}.ipynb")

      println(s"Debug: downloads dir = $downloadsDestination")
      println(s"Debug: downFile = ${downFile.getAbsolutePath}")
      println(s"Debug: uniqueDownFile = ${uniqueDownFile.getAbsolutePath}")







      moveFile(downFile, uniqueDownFile)
      uniqueDownFile.deleteOnExit()

      // output for this notebook includes an IP address which can vary
      compareFilesExcludingIPs(upFile, uniqueDownFile)
    }
  }
}
