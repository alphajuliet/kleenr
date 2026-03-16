;; ============================================================
;; Kleenr — A text cleaning tool built with Scittle + Reagent
;; All processing is client-side. No data leaves your browser.
;; ============================================================

(require '[reagent.core :as r]
         '[reagent.dom :as rdom]
         '[clojure.string :as str])

;; --- Helpers ---

(defn word-count [text]
  (if (str/blank? text)
    0
    (count (re-seq (js/RegExp. "\\S+" "g") text))))

(defn line-count [text]
  (if (empty? text)
    0
    (count (str/split-lines text))))

(defn mac? []
  (let [p (.-platform js/navigator)]
    (or (str/includes? (str p) "Mac")
        (str/includes? (str p) "iPhone")
        (str/includes? (str p) "iPad"))))

(def mod-key (if (mac?) "Cmd" "Ctrl"))

;; --- State ---

(defonce app-state
  (r/atom {:text ""
           :undo-stack []
           :redo-stack []
           :category :whitespace
           :regex-pattern ""
           :regex-replace ""
           :regex-flags {:g true :i false :m false}
           :regex-error nil
           :status nil
           :tab-width 4}))

;; --- Undo / Redo ---

(def max-undo 100)

(defn push-undo! []
  (let [current (:text @app-state)]
    (swap! app-state update :undo-stack
           (fn [stack]
             (let [s (conj stack current)]
               (if (> (count s) max-undo)
                 (vec (drop 1 s))
                 s))))
    (swap! app-state assoc :redo-stack [])))

(defn apply-op! [op-fn & args]
  (let [old-text (:text @app-state)
        new-text (apply op-fn old-text args)]
    (when (not= old-text new-text)
      (push-undo!)
      (swap! app-state assoc :text new-text))))

(defn undo! []
  (let [{:keys [undo-stack text]} @app-state]
    (when (seq undo-stack)
      (swap! app-state
             (fn [s]
               (-> s
                   (assoc :text (peek undo-stack))
                   (update :undo-stack pop)
                   (update :redo-stack conj text)))))))

(defn redo! []
  (let [{:keys [redo-stack text]} @app-state]
    (when (seq redo-stack)
      (swap! app-state
             (fn [s]
               (-> s
                   (assoc :text (peek redo-stack))
                   (update :redo-stack pop)
                   (update :undo-stack conj text)))))))

;; --- Status flash ---

(defn flash! [msg type]
  (swap! app-state assoc :status {:msg msg :type type})
  (js/setTimeout #(swap! app-state assoc :status nil) 2500))

;; --- Text Operations ---

;; Whitespace
(defn op-remove-extra-spaces [text]
  (str/replace text (js/RegExp. " {2,}" "g") " "))

(defn op-trim-lines [text]
  (->> (str/split-lines text)
       (map str/trim)
       (str/join "\n")))

(defn op-remove-empty-lines [text]
  (->> (str/split-lines text)
       (remove str/blank?)
       (str/join "\n")))

(defn op-remove-all-linebreaks [text]
  (str/replace text (js/RegExp. "\\n" "g") " "))

(defn op-fix-linebreaks [text]
  (str/replace text (js/RegExp. "\\r\\n?" "g") "\n"))

(defn op-join-paragraphs [text]
  ;; Preserve double newlines (paragraph breaks), join single newlines
  (let [fixed (op-fix-linebreaks text)]
    (-> fixed
        (str/replace (js/RegExp. "\\n\\n" "g") "\u0000\u0000") ;; protect paragraph breaks
        (str/replace (js/RegExp. "\\n" "g") " ")
        (str/replace (js/RegExp. "\\u0000\\u0000" "g") "\n\n"))))

(defn op-join-lines-comma [text]
  ;; Preserve double newlines (paragraph breaks), join single newlines with ", "
  (let [fixed (op-fix-linebreaks text)]
    (-> fixed
        (str/replace (js/RegExp. "\\n\\n" "g") "\u0000\u0000")
        (str/replace (js/RegExp. "\\n" "g") ", ")
        (str/replace (js/RegExp. "\\u0000\\u0000" "g") "\n\n"))))

(defn op-single-to-double-returns [text]
  (str/replace text (js/RegExp. "\\n" "g") "\n\n"))

(defn op-double-to-single-returns [text]
  (str/replace text (js/RegExp. "\\n\\n" "g") "\n"))

(defn op-smart-clean [text]
  (-> text
      op-fix-linebreaks
      op-remove-extra-spaces
      op-trim-lines
      op-remove-empty-lines))

;; Tabs & Indentation
(defn op-tabs-to-spaces [text]
  (let [w (:tab-width @app-state)
        spaces (apply str (repeat w " "))]
    (str/replace text "\t" spaces)))

(defn op-spaces-to-tabs [text]
  (let [w (:tab-width @app-state)
        re (js/RegExp. (str "^( {" w "})") "gm")]
    ;; Repeatedly replace leading space groups
    (loop [t text prev nil]
      (if (= t prev)
        t
        (recur (str/replace t re "\t") t)))))

(defn op-increase-indent [text]
  (let [w (:tab-width @app-state)
        spaces (apply str (repeat w " "))]
    (->> (str/split-lines text)
         (map #(str spaces %))
         (str/join "\n"))))

(defn op-decrease-indent [text]
  (let [w (:tab-width @app-state)
        re (js/RegExp. (str "^ {1," w "}") "")]
    (->> (str/split-lines text)
         (map #(str/replace % re ""))
         (str/join "\n"))))

;; Case
(defn op-uppercase [text] (str/upper-case text))
(defn op-lowercase [text] (str/lower-case text))

(defn op-title-case [text]
  (str/replace text (js/RegExp. "\\b\\w" "g")
               (fn [m] (str/upper-case m))))

(defn op-sentence-case [text]
  (let [lower (str/lower-case text)]
    ;; Capitalize first char and chars after sentence endings
    (str/replace lower (js/RegExp. "(^|[.!?]\\s+)(\\w)" "g")
                 (fn [match _p1 _p2]
                   ;; match contains full match; uppercase last char
                   (let [last-ch (subs match (dec (count match)))]
                     (str (subs match 0 (dec (count match)))
                          (str/upper-case last-ch)))))))

(defn op-random-case [text]
  (apply str (map (fn [c]
                    (if (< (js/Math.random) 0.5)
                      (str/upper-case (str c))
                      (str/lower-case (str c))))
                  text)))

;; Quotes
(defn op-straight-to-curly [text]
  ;; Context-sensitive: opening after whitespace/start, closing before whitespace/end/punctuation
  (-> text
      ;; Double quotes
      (str/replace (js/RegExp. "(^|[\\s(\\[{])(\")" "gm")
                   (fn [m p1 _p2] (str p1 "\u201C")))
      (str/replace (js/RegExp. "\"" "g") "\u201D")
      ;; Single quotes / apostrophes
      (str/replace (js/RegExp. "(^|[\\s(\\[{])(')" "gm")
                   (fn [m p1 _p2] (str p1 "\u2018")))
      (str/replace (js/RegExp. "'" "g") "\u2019")))

(defn op-curly-to-straight [text]
  (-> text
      (str/replace (js/RegExp. "[\u201C\u201D]" "g") "\"")
      (str/replace (js/RegExp. "[\u2018\u2019]" "g") "'")))

(defn op-single-to-double-quotes [text]
  (-> text
      (str/replace (js/RegExp. "\u2018" "g") "\u201C")
      (str/replace (js/RegExp. "\u2019" "g") "\u201D")
      (str/replace "'" "\"")))

(defn op-double-to-single-quotes [text]
  (-> text
      (str/replace (js/RegExp. "\u201C" "g") "\u2018")
      (str/replace (js/RegExp. "\u201D" "g") "\u2019")
      (str/replace "\"" "'")))

;; Lines / Sorting
(defn op-sort-asc [text]
  (->> (str/split-lines text) sort (str/join "\n")))

(defn op-sort-desc [text]
  (->> (str/split-lines text) sort reverse (str/join "\n")))

(defn op-reverse-lines [text]
  (->> (str/split-lines text) reverse (str/join "\n")))

(defn op-deduplicate-lines [text]
  (->> (str/split-lines text) distinct (str/join "\n")))

(defn op-number-lines [text]
  (->> (str/split-lines text)
       (map-indexed (fn [i line] (str (inc i) ". " line)))
       (str/join "\n")))

;; Encoding / Conversion
(defn op-html-to-plain [text]
  (let [parser (js/DOMParser.)
        doc (.parseFromString parser text "text/html")]
    (.-textContent (.-body doc))))

(defn op-url-encode [text] (js/encodeURIComponent text))
(defn op-url-decode [text]
  (try (js/decodeURIComponent text)
       (catch :default e text)))

(defn op-rot13 [text]
  (apply str
    (map (fn [c]
           (let [code (.charCodeAt (str c) 0)]
             (cond
               (and (>= code 65) (<= code 90))
               (js/String.fromCharCode (+ 65 (mod (+ (- code 65) 13) 26)))
               (and (>= code 97) (<= code 122))
               (js/String.fromCharCode (+ 97 (mod (+ (- code 97) 13) 26)))
               :else (str c))))
         text)))

(defn op-reverse-text [text]
  (apply str (reverse text)))

(defn op-ellipsis-to-periods [text]
  (str/replace text "\u2026" "..."))

(defn op-periods-to-ellipsis [text]
  (str/replace text "..." "\u2026"))

(defn op-add-email-quote [text]
  (->> (str/split-lines text)
       (map #(str "> " %))
       (str/join "\n")))

(defn op-remove-email-quote [text]
  (->> (str/split-lines text)
       (map #(str/replace % (js/RegExp. "^>\\s?" "") ""))
       (str/join "\n")))

(defn op-remove-bullets [text]
  (->> (str/split-lines text)
       (map #(str/replace % (js/RegExp. "^(\\s*)[*\\-+•·◦‣▪▸]\\s*") "$1"))
       (str/join "\n")))

(defn op-add-bullets [text]
  (let [w (:tab-width @app-state)]
    (->> (str/split-lines text)
         (map (fn [line]
                (let [m (.match line (js/RegExp. "^(\\s*)(.*)$"))
                      indent (aget m 1)
                      content (aget m 2)
                      tab-indent (loop [s indent prev nil]
                                   (if (= s prev)
                                     s
                                     (recur (str/replace s (js/RegExp. (str " {" w "}")) "\t") s)))]
                  (str tab-indent "* " content))))
         (str/join "\n"))))

;; --- Regex ---

(defn build-flags []
  (let [f (:regex-flags @app-state)]
    (str (when (:g f) "g")
         (when (:i f) "i")
         (when (:m f) "m"))))

(defn try-regex [pattern flags]
  (try
    (let [re (js/RegExp. pattern flags)]
      (swap! app-state assoc :regex-error nil)
      re)
    (catch :default e
      (swap! app-state assoc :regex-error (.-message e))
      nil)))

(defn regex-match-count []
  (let [{:keys [text regex-pattern]} @app-state
        flags (build-flags)]
    (when (and (not (str/blank? regex-pattern))
               (not (str/blank? text)))
      (let [re (try-regex regex-pattern (if (str/includes? flags "g") flags (str flags "g")))]
        (when re
          (let [matches (.match text re)]
            (if matches (.-length matches) 0)))))))

(defn op-regex-replace [text]
  (let [{:keys [regex-pattern regex-replace]} @app-state
        flags (build-flags)
        re (try-regex regex-pattern flags)]
    (if re
      (.replace text re regex-replace)
      text)))

;; --- Common Regex Patterns ---

(def common-patterns
  [{:label "Choose a pattern..." :pattern "" :replace ""}
   {:label "Email addresses" :pattern "[\\w.+-]+@[\\w-]+\\.[\\w.]+" :replace ""}
   {:label "URLs" :pattern "https?://[\\S]+" :replace ""}
   {:label "IP addresses" :pattern "\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b" :replace ""}
   {:label "HTML tags" :pattern "<[^>]+>" :replace ""}
   {:label "Leading whitespace" :pattern "^\\s+" :replace ""}
   {:label "Trailing whitespace" :pattern "\\s+$" :replace ""}
   {:label "Multiple spaces" :pattern " {2,}" :replace " "}
   {:label "Blank lines" :pattern "\\n{2,}" :replace "\\n"}
   {:label "Digits only" :pattern "\\d+" :replace ""}
   {:label "Non-alphanumeric" :pattern "[^a-zA-Z0-9\\s]" :replace ""}
   {:label "Date (YYYY-MM-DD)" :pattern "\\d{4}-\\d{2}-\\d{2}" :replace ""}
   {:label "Phone (US)" :pattern "\\(?\\d{3}\\)?[-\\s.]?\\d{3}[-\\s.]?\\d{4}" :replace ""}])

;; --- Operations Registry ---

(def operations
  {:whitespace
   [{:label "Smart Clean" :fn op-smart-clean}
    {:label "Remove Extra Spaces" :fn op-remove-extra-spaces}
    {:label "Trim Lines" :fn op-trim-lines}
    {:label "Remove Empty Lines" :fn op-remove-empty-lines}
    {:label "Remove Line Breaks" :fn op-remove-all-linebreaks}
    {:label "Fix Line Breaks" :fn op-fix-linebreaks}
    {:label "Join Paragraphs" :fn op-join-paragraphs}
    {:label "Join Lines with Comma" :fn op-join-lines-comma}
    {:label "1→2 Returns" :fn op-single-to-double-returns}
    {:label "2→1 Returns" :fn op-double-to-single-returns}]
   :tabs
   [{:label "Tabs → Spaces" :fn op-tabs-to-spaces}
    {:label "Spaces → Tabs" :fn op-spaces-to-tabs}
    {:label "Increase Indent" :fn op-increase-indent}
    {:label "Decrease Indent" :fn op-decrease-indent}]
   :case
   [{:label "UPPERCASE" :fn op-uppercase}
    {:label "lowercase" :fn op-lowercase}
    {:label "Title Case" :fn op-title-case}
    {:label "Sentence case" :fn op-sentence-case}
    {:label "rAnDoM cAsE" :fn op-random-case}]
   :quotes
   [{:label "Straight → Curly" :fn op-straight-to-curly}
    {:label "Curly → Straight" :fn op-curly-to-straight}
    {:label "Single → Double" :fn op-single-to-double-quotes}
    {:label "Double → Single" :fn op-double-to-single-quotes}]
   :lines
   [{:label "Sort A→Z" :fn op-sort-asc}
    {:label "Sort Z→A" :fn op-sort-desc}
    {:label "Reverse Lines" :fn op-reverse-lines}
    {:label "Deduplicate" :fn op-deduplicate-lines}
    {:label "Number Lines" :fn op-number-lines}
    {:label "Add > Prefix" :fn op-add-email-quote}
    {:label "Remove > Prefix" :fn op-remove-email-quote}
    {:label "Add Bullets" :fn op-add-bullets}
    {:label "Remove Bullets" :fn op-remove-bullets}]
   :encoding
   [{:label "HTML → Plain" :fn op-html-to-plain}
    {:label "URL Encode" :fn op-url-encode}
    {:label "URL Decode" :fn op-url-decode}
    {:label "ROT13" :fn op-rot13}
    {:label "Reverse Text" :fn op-reverse-text}
    {:label "… → ..." :fn op-ellipsis-to-periods}
    {:label "... → …" :fn op-periods-to-ellipsis}]})

(def category-labels
  {:whitespace "Whitespace"
   :tabs "Tabs"
   :case "Case"
   :quotes "Quotes"
   :lines "Lines"
   :encoding "Encoding"
   :regex "Regex"})

;; --- Components ---

(defn undo-icon []
  [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24"
         :fill "none" :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:polyline {:points "1 4 1 10 7 10"}]
   [:path {:d "M3.51 15a9 9 0 1 0 2.13-9.36L1 10"}]])

(defn redo-icon []
  [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24"
         :fill "none" :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:polyline {:points "23 4 23 10 17 10"}]
   [:path {:d "M20.49 15a9 9 0 1 1-2.12-9.36L23 10"}]])

(defn copy-icon []
  [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24"
         :fill "none" :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:rect {:x "9" :y "9" :width "13" :height "13" :rx "2" :ry "2"}]
   [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]])

(defn paste-icon []
  [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24"
         :fill "none" :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"}]
   [:rect {:x "8" :y "2" :width "8" :height "4" :rx "1" :ry "1"}]])

(defn header-component []
  (let [{:keys [undo-stack redo-stack]} @app-state]
    [:div.header
     [:div.header-left
      [:h1 "Kleenr"]
      [:span.badge "local only"]]
     [:div.header-actions
      [:button.btn.btn-icon
       {:title (str "Undo (" mod-key "+Z)")
        :disabled (empty? undo-stack)
        :on-click undo!}
       [undo-icon]]
      [:button.btn.btn-icon
       {:title (str "Redo (" mod-key "+Shift+Z)")
        :disabled (empty? redo-stack)
        :on-click redo!}
       [redo-icon]]]]))

(defn copy-to-clipboard! []
  (let [text (:text @app-state)]
    (-> (js/navigator.clipboard.writeText text)
        (.then #(flash! "Copied to clipboard" "success"))
        (.catch #(flash! "Copy failed" "error")))))

(defn paste-from-clipboard! []
  (-> (js/navigator.clipboard.readText)
      (.then (fn [text]
               (push-undo!)
               (swap! app-state assoc :text text)
               (flash! "Pasted from clipboard" "success")))
      (.catch #(flash! "Paste failed — check clipboard permissions" "error"))))

(defn text-area-component []
  [:div.textarea-wrapper
   [:div.copy-float
    [:button.btn.btn-sm.btn-icon
     {:title "Paste from clipboard"
      :on-click paste-from-clipboard!}
     [paste-icon]]
    [:button.btn.btn-sm.btn-icon
     {:title "Copy to clipboard"
      :on-click copy-to-clipboard!}
     [copy-icon]]]
   [:textarea.main-textarea
    {:value (:text @app-state)
     :placeholder "Paste or type your text here..."
     :spell-check "false"
     :on-change (fn [e]
                  (let [new-val (.. e -target -value)
                        old-val (:text @app-state)]
                    ;; Only push undo for substantial changes (not every keystroke)
                    ;; We'll push undo on blur or on operation instead
                    (swap! app-state assoc :text new-val)))
     :on-blur (fn [_]
                ;; Push undo state when user finishes typing
                ;; (only if text changed since last undo push)
                (let [stack (:undo-stack @app-state)
                      text (:text @app-state)]
                  (when (or (empty? stack)
                            (not= (peek stack) text))
                    ;; Don't clear redo here since this is typing, not an operation
                    (swap! app-state update :undo-stack
                           (fn [s]
                             (let [s2 (conj s text)]
                               (if (> (count s2) max-undo)
                                 (vec (drop 1 s2))
                                 s2)))))))}]])

(defn stats-bar-component []
  (let [text (:text @app-state)]
    [:div.stats-bar
     [:span [:strong (count text)] " chars"]
     [:span [:strong (word-count text)] " words"]
     [:span [:strong (line-count text)] " lines"]]))

(defn status-component []
  (let [status (:status @app-state)]
    (when status
      [:div.status-msg {:class (:type status)}
       (:msg status)])))

(defn category-tabs-component []
  (let [current (:category @app-state)]
    [:div.category-tabs
     (for [cat [:whitespace :tabs :case :quotes :lines :encoding :regex]]
       ^{:key cat}
       [:button.cat-tab
        {:class (when (= cat current) "active")
         :on-click #(swap! app-state assoc :category cat)}
        (get category-labels cat)])]))

(defn tab-width-config []
  [:div.config-row
   [:label "Tab width:"]
   [:input.config-input
    {:type "number" :min 1 :max 16
     :value (:tab-width @app-state)
     :on-change (fn [e]
                  (let [v (js/parseInt (.. e -target -value))]
                    (when (and (not (js/isNaN v)) (> v 0) (<= v 16))
                      (swap! app-state assoc :tab-width v))))}]])

(defn ops-buttons-component []
  (let [cat (:category @app-state)
        ops (get operations cat)]
    (when ops
      [:div
       (when (= cat :tabs) [tab-width-config])
       [:div.ops-grid
        (for [{:keys [label] :as op} ops]
          ^{:key label}
          [:button.op-btn
           {:on-click (fn []
                        (apply-op! (:fn op))
                        (flash! (str label " applied") "success"))}
           label])]])))

(defn regex-panel-component []
  (let [{:keys [regex-pattern regex-replace regex-flags regex-error text]} @app-state
        match-ct (regex-match-count)]
    [:div.regex-panel
     [:h3 "Regular Expression"]
     [:div.regex-row
      [:label "Pattern"]
      [:input.regex-input
       {:type "text"
        :placeholder "e.g. \\b\\w+\\b"
        :value regex-pattern
        :on-change #(swap! app-state assoc :regex-pattern (.. % -target -value))}]]
     [:div.regex-row
      [:label "Replace"]
      [:input.regex-input
       {:type "text"
        :placeholder "Replacement (use $1, $2 for groups)"
        :value regex-replace
        :on-change #(swap! app-state assoc :regex-replace (.. % -target -value))}]]
     [:div.regex-row
      [:label "Flags"]
      [:div.regex-flags
       [:label
        [:input {:type "checkbox" :checked (:g regex-flags)
                 :on-change #(swap! app-state update-in [:regex-flags :g] not)}]
        "global"]
       [:label
        [:input {:type "checkbox" :checked (:i regex-flags)
                 :on-change #(swap! app-state update-in [:regex-flags :i] not)}]
        "ignore case"]
       [:label
        [:input {:type "checkbox" :checked (:m regex-flags)
                 :on-change #(swap! app-state update-in [:regex-flags :m] not)}]
        "multiline"]]]
     (when regex-error
       [:div.regex-error "⚠ " regex-error])
     [:div.regex-actions
      [:button.btn.btn-accent
       {:disabled (or (str/blank? regex-pattern) (some? regex-error))
        :on-click (fn []
                    (apply-op! op-regex-replace)
                    (flash! "Regex replace applied" "success"))}
       "Replace All"]
      (when (and match-ct (not (str/blank? regex-pattern)))
        [:span.match-count
         (str match-ct " match" (when (not= match-ct 1) "es"))])]

     ;; Common patterns
     [:div.patterns-row
      [:label "Presets"]
      [:select.pattern-select
       {:value ""
        :on-change (fn [e]
                     (let [idx (js/parseInt (.. e -target -value))]
                       (when (and (not (js/isNaN idx)) (pos? idx))
                         (let [p (nth common-patterns idx)]
                           (swap! app-state assoc
                                  :regex-pattern (:pattern p)
                                  :regex-replace (:replace p)
                                  :regex-error nil)))))}
       (for [[i p] (map-indexed vector common-patterns)]
         ^{:key i}
         [:option {:value i} (:label p)])]]]))

(defn shortcuts-component []
  [:div.shortcuts-panel
   [:span [:kbd (str mod-key "+Z")] " Undo"]
   [:span [:kbd (str mod-key "+Shift+Z")] " Redo"]
   [:span [:kbd (str mod-key "+A")] " Select all"]
   [:span [:kbd (str mod-key "+C")] " Copy"]])

(defn privacy-note-component []
  [:div.privacy-note
   [:span.lock "🔒 "]
   "All processing happens locally in your browser. No text is sent to any server."])

(defn app []
  [:div.app-container
   [header-component]
   [text-area-component]
   [stats-bar-component]
   [status-component]
   [category-tabs-component]
   (if (= :regex (:category @app-state))
     [regex-panel-component]
     [ops-buttons-component])
   [shortcuts-component]
   [privacy-note-component]])

;; --- Keyboard Shortcuts ---

(.addEventListener js/document "keydown"
  (fn [e]
    (let [meta? (if (mac?) (.-metaKey e) (.-ctrlKey e))
          shift? (.-shiftKey e)
          key (.-key e)]
      (when meta?
        (cond
          (and (= key "z") (not shift?))
          (do (.preventDefault e) (undo!))

          (and (or (= key "z") (= key "Z")) shift?)
          (do (.preventDefault e) (redo!))

          (and (= key "y") (not shift?))
          (do (.preventDefault e) (redo!)))))))

;; --- Mount ---

(rdom/render [app] (.getElementById js/document "app"))
