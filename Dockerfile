FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY *.java ./src/
COPY index.html ./web/index.html
RUN mkdir out && javac -d out src/*.java
ENV PORT=10000
EXPOSE 10000
CMD ["java", "-cp", "out", "Main"]
