(ns bartnet.protobuilder
  (:require [schema.core :as s]
            [clj-time.core :as t]
            [clojure.tools.logging :as log]
            [clj-time.coerce :as c]
            [schema.utils :as su]
            [ring.swagger.json-schema :as js]
            [bartnet.util :refer [if-and-let]])
  (:import (com.google.protobuf GeneratedMessage$Builder WireFormat$JavaType Descriptors$FieldDescriptor ByteString GeneratedMessage ProtocolMessageEnum Descriptors$Descriptor Descriptors$EnumDescriptor)
           (java.nio ByteBuffer)
           (io.netty.buffer ByteBuf)
           (clojure.lang Reflector)
           (co.opsee.proto Any)))

(defn- byte-string [buf]
  (cond
    (instance? ByteBuffer buf) (ByteString/copyFrom ^ByteBuffer buf)
    (instance? ByteBuf buf) (ByteString/copyFrom (.nioBuffer buf))
    true (ByteString/copyFrom (bytes buf))))

(defn- enum-type [^Descriptors$FieldDescriptor field v]
  (let [enum (.getEnumType field)]
    (cond
      (integer? v) (.findValueByNumber enum v)
      (string? v) (.findValueByName enum v)
      (symbol? v) (.findValueByName enum (name v)))))

(defmacro case-enum
  "Like `case`, but explicitly dispatch on Java enum ordinals."
  [e & clauses]
  (letfn [(enum-ordinal [e] `(let [^Enum e# ~e] (.ordinal e#)))]
    `(case ~(enum-ordinal e)
       ~@(concat
           (mapcat (fn [[test result]]
                     [(eval (enum-ordinal test)) result])
                   (partition 2 clauses))
           (when (odd? (count clauses))
             (list (last clauses)))))))

(declare hash->proto)
(declare proto->hash)

(defmulti ^GeneratedMessage$Builder into-builder class)
(defmethod into-builder Class [^Class c] (Reflector/invokeStaticMethod c "newBuilder" (to-array nil)))
(defmethod into-builder GeneratedMessage$Builder [b] b)

(defn hash->anyhash
  "Searches co.opsee.proto.* for the type element of the hash, brings back its builder, builds it and delivers
  it marshalled into the value element of the hash"
  [hash]
  (let [clazz (Class/forName (str "co.opsee.proto." (:type_url hash)))
        proto (hash->proto clazz (:value hash))]
    {:type_url (:type_url hash) :value (.toByteArray proto)}))


(defn tim->adder [tim]
  (case tim "d" t/days
            "h" t/hours
            "m" t/minutes
            "s" t/seconds
            "u" t/millis))

(defmulti parse-deadline class)
(defmethod parse-deadline String [value]
  (let [[_ dec tim] (re-matches #"([0-9]+)([smhdu])" value)
        adder (tim->adder tim)
        date (t/plus (t/now) (adder (Integer/parseInt dec)))]
    {:seconds (c/to-epoch date) :nanos 0}))
(defmethod parse-deadline :default [value]
  value)

(defn value-converter [v builder field]
  (case-enum (.getJavaType field)
             WireFormat$JavaType/BOOLEAN (boolean v)
             WireFormat$JavaType/BYTE_STRING (byte-string v)
             WireFormat$JavaType/DOUBLE (double v)
             WireFormat$JavaType/ENUM (enum-type field v)
             WireFormat$JavaType/FLOAT (float v)
             WireFormat$JavaType/INT (int v)
             WireFormat$JavaType/LONG (long v)
             WireFormat$JavaType/STRING (str v)
             WireFormat$JavaType/MESSAGE (case (.getName (.getMessageType field))
                                           "Any" (hash->proto (.newBuilderForField builder field) (hash->anyhash v))
                                           "Timestamp" (hash->proto (.newBuilderForField builder field) (parse-deadline v))
                                           (hash->proto (.newBuilderForField builder field) v))))

(defn- enum-keyword [^ProtocolMessageEnum enum]
  (let [enum-type (.getValueDescriptor enum)]
    (keyword (.getName enum-type))))

(defn hash->proto [proto msg]
  (let [builder (into-builder proto)
        descriptor (.getDescriptorForType builder)]
    (doseq[[k v] msg
          :let [name (name k)]]
      (when-let [field (.findFieldByName descriptor name)]
        (if (.isRepeated field)
          (doseq [va (flatten [v])]
            (.addRepeatedField builder field (value-converter va builder field)))
          (.setField builder field (value-converter v builder field)))))
    (.build builder)))

(defn- any->hash [^Any any]
  (let [type (.getTypeUrl any)
        clazz (Class/forName (str "co.opsee.proto." type))
        proto (Reflector/invokeStaticMethod clazz "parseFrom" (to-array [(.getValue any)]))]
    {:type_url type
     :value (proto->hash proto)}))

(defn- unpack-value [^Descriptors$FieldDescriptor field value]
  (case-enum (.getJavaType field)
             WireFormat$JavaType/BOOLEAN value
             WireFormat$JavaType/BYTE_STRING value
             WireFormat$JavaType/DOUBLE value
             WireFormat$JavaType/ENUM (enum-keyword value)
             WireFormat$JavaType/FLOAT value
             WireFormat$JavaType/INT value
             WireFormat$JavaType/LONG value
             WireFormat$JavaType/STRING value
             WireFormat$JavaType/MESSAGE (if (= "Any" (.getName (.getMessageType field)))
                                           (any->hash value)
                                           (proto->hash value))))

(defn- unpack-repeated-or-single [^Descriptors$FieldDescriptor field value]
  (if (.isRepeated field)
    (mapv (partial unpack-value field) value)
    (unpack-value field value)))

(defn proto->hash [^GeneratedMessage proto]
  (into {}
        (map (fn [[^Descriptors$FieldDescriptor desc value]]
               [(keyword (.getName desc)) (unpack-repeated-or-single desc value)]))
        (.getAllFields proto)))

(declare type->schema)
(declare proto->schema)

(defn- enum->schema [^Descriptors$EnumDescriptor enum]
  (apply s/enum (map #(keyword (.getName %)) (.getValues enum))))

(defn- array-wrap [^Descriptors$FieldDescriptor field]
  (if (.isRepeated field)
    (s/maybe [(type->schema field)])
    (type->schema field)))

(defn- field->schema-entry [^Descriptors$FieldDescriptor field]
  [(s/optional-key (keyword (.getName field))) (if (.isOptional field)
                                                 (s/maybe (array-wrap field))
                                                 (array-wrap field))])

(defn- descriptor->schema [^Descriptors$Descriptor descriptor]
  (into {}
        (map field->schema-entry)
        (.getFields descriptor)))

(defrecord AnyTypeSchema [_]
  s/Schema
  (walker [_]
    (let [walker (atom nil)]
      (reset! walker s/subschema-walker)
      (fn [v]
        (let [name (:type_url v)]
          (if-and-let [clazz (Class/forName (str "co.opsee.proto." name))
                       schema (proto->schema clazz)]
                      {:type_url name
                       :value ((s/start-walker @walker schema) (:value v))})))))
  (explain [_]
    'any))

(defmethod js/json-type AnyTypeSchema [_] {:type "void"})


(defn- type->schema [^Descriptors$FieldDescriptor field]
  (case-enum (.getJavaType field)
             WireFormat$JavaType/BOOLEAN s/Bool
             WireFormat$JavaType/BYTE_STRING s/Str
             WireFormat$JavaType/DOUBLE s/Num
             WireFormat$JavaType/ENUM (enum->schema (.getEnumType field))
             WireFormat$JavaType/FLOAT s/Num
             WireFormat$JavaType/INT s/Int
             WireFormat$JavaType/LONG s/Int
             WireFormat$JavaType/STRING s/Str
             WireFormat$JavaType/MESSAGE (case (.getName (.getMessageType field))
                                           "Any" (AnyTypeSchema. nil)
                                           "Timestamp" (s/either (descriptor->schema (.getMessageType field))
                                                                 s/Str)
                                           (descriptor->schema (.getMessageType field)))))

(defn proto->schema [^Class clazz]
  (let [^Descriptors$Descriptor descriptor (Reflector/invokeStaticMethod clazz "getDescriptor" (to-array nil))]
    (descriptor->schema descriptor)))

(defn proto-walker [^Class clazz]
  (let [first (atom 0)]
    (s/start-walker
      (fn [schema]
        (let [walk (s/walker schema)]
          (fn [data]
            (let [count (swap! first inc)
                  result (walk data)]
              (log/debugf "%s | checking %s against %s\n",
                      (if (su/error? result) "FAIL" "PASS")
                      data (s/explain schema))
              (if (or (su/error? result)
                      (< 1 count))
                result
                (hash->proto clazz data))))))
      (proto->schema clazz))))