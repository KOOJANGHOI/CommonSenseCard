package common

object Env extends Enumeration {
  val Dev = Value("development")
  val Test = Value("test")
  val Staging = Value("staging")
  val Prod = Value("production")

  implicit class ValueExt(value: Value) {
    def isDev = value == Dev
    def isTest = value == Test
    def isProd = value == Prod || value == Staging
  }
}

object Config {
  final val ApiCustomScheme = "COMMONSENSE-AUTH-TOKENS"
  final val UserAuthTokenKey = "commonsense_user_auth_token"

  final val JwtSecret = "ab8LCBmK79tWYgcr3zjtZUzoAEvXeWuCd2cLEyKJJ96h"
  final val UserIdLogKey = "usr_id"

  /* Constants */
  final val CommonSenseDBConfigPath = "commonsense_db"
}
