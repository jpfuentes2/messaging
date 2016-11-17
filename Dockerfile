FROM clojure:latest

RUN mkdir -p /build
WORKDIR /build
COPY . /build

RUN mkdir -p /messaging
WORKDIR /messaging
RUN mv /build/target/messaging.jar /messaging
RUN rm  -rf /build

CMD java $JAVA_OPTS -jar messaging.jar
