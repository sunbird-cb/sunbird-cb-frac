FROM openjdk:8

RUN apt-get update \
    && apt-get install -y \
        curl \
        libxrender1 \
        libjpeg62-turbo \
        fontconfig \
        libxtst6 \
        xfonts-75dpi \
        xfonts-base \
        xz-utils

RUN curl "https://github.com/wkhtmltopdf/packaging/releases/download/0.12.6-1/wkhtmltox_0.12.6-1.buster_amd64.deb" -L -o "wkhtmltopdf.deb"
RUN dpkg -i wkhtmltopdf.deb

COPY entity-0.0.1-SNAPSHOT.jar /opt/
CMD ["java", "-XX:+PrintFlagsFinal", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-jar", "/opt/entity-0.0.1-SNAPSHOT.jar"]
