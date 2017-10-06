package org.broadinstitute.dsde.workbench.page

import org.broadinstitute.dsde.workbench.config.AuthToken
import org.broadinstitute.dsde.workbench.util.WebBrowserUtil
import org.openqa.selenium.WebDriver
import org.scalatest.selenium.Page

trait CookieAuthedPage[P <: Page] extends Page with PageUtil[P] with WebBrowserUtil { self: P =>
  implicit val authToken: AuthToken

  // always use open() to access a CookieAuthedPage - `go to` will not set the cookie
  override def open(implicit webDriver: WebDriver): P = {
    go to this
    addCookie("FCToken", authToken.value)
    super.open
  }
}
