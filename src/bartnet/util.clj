(ns bartnet.util)

(defn if-and-let*
  [bindings then-clause else-clause deshadower]
  (if (empty? bindings)
    then-clause
    `(if-let ~(vec (take 2 bindings))
       ~(if-and-let* (drop 2 bindings) then-clause else-clause deshadower)
       (let ~(vec (apply concat deshadower))
         ~else-clause))))

(defmacro try-let
  "A combination of try and let such that exceptions thrown in the binding or
   body can be handled by catch clauses in the body, and all bindings are
   available to the catch and finally clauses. If an exception is thrown while
   evaluating a binding value, it and all subsequent binding values will be nil.
   Example:
   (try-let [x (f a)
             y (f b)]
     (g x y)
     (catch Exception e (println a b x y e)))"
  {:arglists '([[bindings*] exprs* catch-clauses* finally-clause?])}
  [bindings & exprs]
  (when-not (even? (count bindings))
    (throw (IllegalArgumentException. "try-let requires an even number of forms in binding vector")))
  (let [names  (take-nth 2 bindings)
        values (take-nth 2 (next bindings))
        ex     (gensym "ex__")]
    `(let [~ex nil
           ~@(interleave names (repeat nil))
           ~@(interleave
               (map vector names (repeat ex))
               (for [v values]
                 `(if ~ex
                    [nil ~ex]
                    (try [~v nil]
                         (catch Throwable ~ex [nil ~ex])))))]
       (try
         (when ~ex (throw ~ex))
         ~@exprs))))

(defmacro if-and-let
  ([bindings then-clause]
   `(if-and-let ~bindings ~then-clause nil))
  ([bindings then-clause else-clause]
   (let [shadowed-syms (filter #(or ((or &env {}) %) (resolve %))
                               (filter symbol?
                                       (tree-seq coll? seq (take-nth 2 bindings))))
         deshadower (zipmap shadowed-syms (repeatedly gensym))]
     `(let ~(vec (apply concat (map (fn [[k v]] [v k]) deshadower)))
        ~(if-and-let* bindings then-clause else-clause deshadower)))))

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))
