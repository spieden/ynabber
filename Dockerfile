FROM clojure

RUN mkdir /connector
WORKDIR /connector

ADD deps.edn .
RUN clojure -Stree

ADD src src

ENTRYPOINT ["sh", "-c", "cd /connector; exec clojure -M:airbyte-proto $@", "--"]
