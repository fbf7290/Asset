package com.ktmet.asset.common.api

import play.api.libs.json.{Format, JsResult, JsSuccess, JsValue, Json, Reads, Writes}

object MapFormat {

  def read[K, V](jsonToObjectKey: (String, V) => K)(implicit f: Format[V]): Reads[Map[K, V]] =
    new Reads[Map[K, V]] {
      def reads(jv: JsValue): JsResult[Map[K, V]] =
        JsSuccess(jv.as[Map[String, V]].map{case (k, v) =>
          jsonToObjectKey(k, v) -> v.asInstanceOf[V]
        })
    }
  def write[K, V](objectToJsonKey: (K, V) => String)(implicit f: Format[V]): Writes[Map[K, V]] =
    new Writes[Map[K, V]] {
      override def writes(o: Map[K, V]): JsValue =
        Json.obj(o.map{case (k, v) =>
          objectToJsonKey(k, v) -> Json.toJsFieldJsValueWrapper(v)
        }.toSeq:_*)
    }
}
