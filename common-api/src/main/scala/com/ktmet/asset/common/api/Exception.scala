package com.ktmet.asset.common.api


object Exception {

  //  class InternalServerError extends ClientException(500, "InternalServerError", "InternalServerError")
  class InternalServerError(detail:String = "InternalServerError") extends ClientException(500, "InternalServerError", detail)
  class IllegalFileExtensionException extends ClientException(409, "IllegalFileExtensionException", "Check your file extension")
  class ImageFileBadRequestException extends ClientException(400, "ImageFileBadRequestException", "Check your file request")
  class FormBadRequestException extends ClientException(400, "FormBadRequestException", "Check your form request")
  class IllegalBodyException extends ClientException(400, "IllegalBodyException", "Check your http body")

  class NotFoundResourceException extends ClientException(404, "NotFoundResourceException", "Not Found Resource")
  class NotFoundException extends ClientException(404, "NotFoundException", "Not Found")
}
