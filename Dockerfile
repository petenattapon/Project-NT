FROM openjdk:11

WORKDIR /app

COPY . /app

RUN javac City.java

CMD ["java", "City"]
