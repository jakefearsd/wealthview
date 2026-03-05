# Stage 1: Build frontend
FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build backend
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml backend/pom.xml
COPY backend/wealthview-persistence/pom.xml backend/wealthview-persistence/pom.xml
COPY backend/wealthview-core/pom.xml backend/wealthview-core/pom.xml
COPY backend/wealthview-api/pom.xml backend/wealthview-api/pom.xml
COPY backend/wealthview-import/pom.xml backend/wealthview-import/pom.xml
COPY backend/wealthview-projection/pom.xml backend/wealthview-projection/pom.xml
COPY backend/wealthview-app/pom.xml backend/wealthview-app/pom.xml
RUN cd backend && mvn dependency:go-offline -q
COPY backend backend
RUN cd backend && mvn clean package -DskipTests -q

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/backend/wealthview-app/target/*.jar app.jar
COPY --from=frontend-build /app/frontend/dist /app/static
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
