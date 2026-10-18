FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src ./src
COPY web ./web
RUN mkdir out && javac -d out src/*.java
ENV PORT=10000
EXPOSE 10000
CMD ["java", "-cp", "out", "Main"]
