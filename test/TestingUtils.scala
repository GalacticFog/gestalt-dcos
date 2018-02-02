import java.util.UUID

import org.specs2.matcher.JsonMatchers
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.JsValue

trait TestingUtils extends SpecificationLike with JsonMatchers {

  def uuid = UUID.randomUUID()

  def getPublicVar(js: JsValue, name: String): Option[String] = (js \ "properties" \ "config" \ "env" \ "public" \ name).asOpt[String]

  def getPrivateVar(js: JsValue, name: String): Option[String] = (js \ "properties" \ "config" \ "env" \ "private" \ name).asOpt[String]

  def havePublicVar(pair: => (String, String)) = ((_: JsValue).toString) ^^ /("properties") /("config") /("env") /("public") /(pair)

  def havePrivateVar(pair: => (String, String)) = ((_: JsValue).toString) ^^ /("properties") /("config") /("env") /("private") /(pair)

  def notHavePublicVar(name: String) = ((_: JsValue).toString) ^^ not /("properties") /("config") /("env") /("public") /(name -> ".*".r)

  def notHavePrivateVar(name: String) = ((_: JsValue).toString) ^^ not /("properties") /("config") /("env") /("private") /(name -> ".*".r)

  def haveCpus(cpus: => Double) = ((s: JsValue) => (s \ "properties" \ "services" \(0) \"container_spec" \"properties" \ "cpus").asOpt[Double]) ^^ beSome(cpus)

  def haveMem(mem: => Int) = ((s: JsValue) => (s \ "properties" \ "services" \(0) \"container_spec" \"properties" \ "memory").asOpt[Int]) ^^ beSome(mem)

}
