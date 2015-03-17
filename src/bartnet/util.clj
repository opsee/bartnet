(ns bartnet.util)

(defn if-and-let*
  [bindings then-clause else-clause deshadower]
  (if (empty? bindings)
    then-clause
    `(if-let ~(vec (take 2 bindings))
       ~(if-and-let* (drop 2 bindings) then-clause else-clause deshadower)
       (let ~(vec (apply concat deshadower))
         ~else-clause))))

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
