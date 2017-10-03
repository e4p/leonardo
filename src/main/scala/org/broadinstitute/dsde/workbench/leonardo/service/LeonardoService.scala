package org.broadinstitute.dsde.workbench.leonardo.service

import java.io.File

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.leonardo.config.DataprocConfig
import org.broadinstitute.dsde.workbench.leonardo.dao.DataprocDAO
import org.broadinstitute.dsde.workbench.leonardo.db.{DataAccess, DbReference}
import org.broadinstitute.dsde.workbench.leonardo.model.LeonardoJsonSupport._
import org.broadinstitute.dsde.workbench.leonardo.model.StringValueClass.LabelMap
import org.broadinstitute.dsde.workbench.leonardo.model._
import org.broadinstitute.dsde.workbench.leonardo.monitor.ClusterMonitorSupervisor.{ClusterCreated, ClusterDeleted, RegisterLeoService}
import org.broadinstitute.dsde.workbench.google.gcs._
import slick.dbio.DBIO
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class ClusterNotFoundException(googleProject: GoogleProject, clusterName: ClusterName)
  extends LeoException(s"Cluster ${googleProject.string}/${clusterName.string} not found", StatusCodes.NotFound)

case class ClusterAlreadyExistsException(googleProject: GoogleProject, clusterName: ClusterName)
  extends LeoException(s"Cluster ${googleProject.string}/${clusterName.string} already exists", StatusCodes.Conflict)

case class InitializationFileException(googleProject: GoogleProject, clusterName: ClusterName, errorMessage: String)
  extends LeoException(s"Unable to process initialization files for ${googleProject.string}/${clusterName.string}. Returned message: $errorMessage", StatusCodes.Conflict)

case class JupyterExtensionException(gcsUri: GcsPath)
  extends LeoException(s"Jupyter extension URI is invalid or unparseable: ${gcsUri.toUri}", StatusCodes.BadRequest)

case class ParseLabelsException(labelString: String)
  extends LeoException(s"Could not parse label string: $labelString. Expected format [key1=value1,key2=value2,...]", StatusCodes.BadRequest)

class LeonardoService(protected val dataprocConfig: DataprocConfig, gdDAO: DataprocDAO, dbRef: DbReference, val clusterMonitorSupervisor: ActorRef)(implicit val executionContext: ExecutionContext) extends LazyLogging {
  val bucketPathMaxLength = 1024

  // Register this instance with the cluster monitor supervisor so our cluster monitor can potentially delete and recreate clusters
  clusterMonitorSupervisor ! RegisterLeoService(this)

  def createCluster(googleProject: GoogleProject, clusterName: ClusterName, clusterRequest: ClusterRequest): Future[Cluster] = {
    def create() = {
      createGoogleCluster(googleProject, clusterName, clusterRequest) flatMap { case (cluster: Cluster, initBucket: String) =>
        dbRef.inTransaction { dataAccess =>
          dataAccess.clusterQuery.save(cluster, initBucket)
        }
      }
    }

    // Check if the google project has a cluster with the same name. If not, we can create it
    dbRef.inTransaction { dataAccess =>
      dataAccess.clusterQuery.getByName(googleProject, clusterName)
    } flatMap {
      case Some(_) => throw ClusterAlreadyExistsException(googleProject, clusterName)
      case None =>
        create andThen { case Success(cluster) =>
          clusterMonitorSupervisor ! ClusterCreated(cluster)
        }
    }
  }

  def getClusterDetails(googleProject: GoogleProject, clusterName: ClusterName): Future[Cluster] = {
    dbRef.inTransaction { dataAccess =>
      getCluster(googleProject, clusterName, dataAccess)
    }
  }

  def deleteCluster(googleProject: GoogleProject, clusterName: ClusterName): Future[Int] = {
    getClusterDetails(googleProject, clusterName) flatMap { cluster =>
      if(cluster.status.isActive) {
        for {
          _ <- gdDAO.deleteCluster(googleProject, clusterName)
          recordCount <- dbRef.inTransaction(dataAccess => dataAccess.clusterQuery.markPendingDeletion(cluster.googleId))
        } yield {
          clusterMonitorSupervisor ! ClusterDeleted(cluster)
          recordCount
        }
      } else Future.successful(0)
    }
  }

  def listClusters(params: LabelMap): Future[Seq[Cluster]] = {
   processListClustersParameters(params).flatMap { paramMap =>
     dbRef.inTransaction { dataAccess =>
       dataAccess.clusterQuery.listByLabels(paramMap._1, paramMap._2)
     }
   }
  }

  private[service] def getCluster(googleProject: GoogleProject, clusterName: ClusterName, dataAccess: DataAccess): DBIO[Cluster] = {
    dataAccess.clusterQuery.getByName(googleProject, clusterName) flatMap {
      case None => throw ClusterNotFoundException(googleProject, clusterName)
      case Some(cluster) => DBIO.successful(cluster)
    }
  }

  /* Creates a cluster in the given google project:
     - Add a firewall rule to the user's google project if it doesn't exist, so we can access the cluster
     - Create the initialization bucket for the cluster in the leo google project
     - Upload all the necessary initialization files to the bucket
     - Create the cluster in the google project
   Currently, the bucketPath of the clusterRequest is not used - it will be used later as a place to store notebook results */
  private[service] def createGoogleCluster(googleProject: GoogleProject, clusterName: ClusterName, clusterRequest: ClusterRequest)(implicit executionContext: ExecutionContext): Future[(Cluster, String)] = {
    val bucketName = generateUniqueBucketName(clusterName.string)
    for {
      // Validate that the Jupyter extension URI is a valid URI and references a real GCS object
      _ <- validateJupyterExtensionUri(googleProject, clusterRequest.jupyterExtensionUri)
      // Create the firewall rule in the google project if it doesn't already exist, so we can access the cluster
      _ <- gdDAO.updateFirewallRule(googleProject)
      // Create the bucket in leo's google bucket and populate with initialization files
//FIXME merge: google project of bucket??
      initBucketPath <- initializeBucket(GoogleProject(dataprocConfig.leoGoogleBucket), clusterName, bucketName, clusterRequest)
      // Once the bucket is ready, build the cluster
      cluster <- gdDAO.createCluster(googleProject, clusterName, clusterRequest, bucketName).andThen { case Failure(e) =>
        // If cluster creation fails, delete the init bucket asynchronously
        gdDAO.deleteBucket(googleProject, bucketName)
      }
    } yield {
      (cluster, initBucketPath)
    }
  }

  private[service] def validateJupyterExtensionUri(googleProject: GoogleProject, gcsUriOpt: Option[GcsPath])(implicit executionContext: ExecutionContext): Future[Unit] = {
    gcsUriOpt match {
      case None => Future.successful(())
      case Some(gcsPath) =>
        if (gcsPath.toUri.length > bucketPathMaxLength) {
          throw JupyterExtensionException(gcsPath)
        }
        gdDAO.bucketObjectExists(googleProject, gcsPath).map {
          case true => ()
          case false => throw JupyterExtensionException(gcsPath)
        }
    }
  }

  /* Create a google bucket and populate it with init files */
//FIXME merge
  private[service] def initializeBucket(googleProject: GoogleProject, clusterName: ClusterName, bucketName: GcsBucketName, clusterRequest: ClusterRequest): Future[GoogleBucket] = {
    for {
      initBucketPath <- gdDAO.createBucket(googleProject, bucketName)
      _ <- initializeBucketObjects(googleProject, clusterName, bucketName, clusterRequest)
    } yield { initBucketPath }
  }

  /* Process the templated cluster init script and put all initialization files in the init bucket */
  private[service] def initializeBucketObjects(googleProject: GoogleProject, clusterName: ClusterName, bucketName: GcsBucketName, clusterRequest: ClusterRequest): Future[Unit] = {
    val initScriptPath = dataprocConfig.configFolderPath + dataprocConfig.initActionsScriptName
    val replacements = ClusterInitValues(googleProject, clusterName, bucketName, dataprocConfig, clusterRequest).toJson.asJsObject.fields
    val filesToUpload = List(dataprocConfig.jupyterServerCrtName, dataprocConfig.jupyterServerKeyName, dataprocConfig.jupyterRootCaPemName,
      dataprocConfig.clusterDockerComposeName, dataprocConfig.jupyterProxySiteConfName, dataprocConfig.jupyterInstallExtensionScript,
      dataprocConfig.userServiceAccountCredentials)

    for {
      // Fill in templated fields in the init script with the given replacements
      content <- template(initScriptPath, replacements)
      // Upload the init script itself to the bucket
      _ <- gdDAO.uploadToBucket(googleProject, GcsPath(bucketName, GcsRelativePath(dataprocConfig.initActionsScriptName)), content)
      // Upload ancillary files like the certs, cluster docker compose file, site.conf, etc to the init bucket
      _ <- Future.traverse(filesToUpload)(name => gdDAO.uploadToBucket(googleProject, GcsPath(bucketName, GcsRelativePath(name)), new File(dataprocConfig.configFolderPath, name)))
    } yield ()
  }

  /* Process a file using map of replacement values. Each value in the replacement map replaces it's key in the file*/
  private[service] def template(filePath: String, replacementMap: Map[String, JsValue]): Future[String] = {
    Future {
      val raw = scala.io.Source.fromFile(filePath).mkString
      replacementMap.foldLeft(raw)((a, b) => a.replaceAllLiterally("$(" + b._1 + ")", b._2.toString()))
    }
  }

  private[service] def processListClustersParameters(params: LabelMap): Future[(LabelMap, Boolean)] = {
    val includeDeletedKey = "includeDeleted"
    Future {
      params.get(includeDeletedKey) match {
        case Some(includeDeleted) => (processLabelMap(params - includeDeletedKey), includeDeleted.toBoolean)
        case None => (processLabelMap(params), false)
      }
    }
  }

  /**
    * There are 2 styles of passing labels to the list clusters endpoint:
    *
    * 1. As top-level query string parameters: GET /api/clusters?foo=bar&baz=biz
    * 2. Using the _labels query string parameter: GET /api/clusters?_labels=foo%3Dbar,baz%3Dbiz
    *
    * The latter style exists because Swagger doesn't provide a way to specify free-form query string
    * params. This method handles both styles, and returns a Map[String, String] representing the labels.
    *
    * Note that style 2 takes precedence: if _labels is present on the query string, any additional
    * parameters are ignored.
    *
    * @param params raw query string params
    * @return a Map[String, String] representing the labels
    */
  private[service] def processLabelMap(params: LabelMap): LabelMap = {
    params.get("_labels") match {
      case Some(extraLabels) =>
        extraLabels.split(',').foldLeft(Map.empty[String, String]) { (r, c) =>
          c.split('=') match {
            case Array(key, value) => r + (key -> value)
            case _ => throw ParseLabelsException(extraLabels)
          }
        }
      case None => params
    }
  }
}
