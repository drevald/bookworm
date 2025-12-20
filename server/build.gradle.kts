plugins {
    id("java")
    id("org.springframework.boot") version "3.2.3"
    id("io.spring.dependency-management") version "1.1.4"
    id("com.google.protobuf") version "0.9.4"
}

group = "com.homelibrary"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("net.devh:grpc-server-spring-boot-starter:3.1.0.RELEASE")
    
    runtimeOnly("org.postgresql:postgresql")
    
    implementation("net.sourceforge.tess4j:tess4j:5.10.0")
    
    // JAI Image I/O for Tesseract support
    implementation("com.github.jai-imageio:jai-imageio-core:1.4.0")
    implementation("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")
    
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("io.grpc:grpc-protobuf:1.60.0")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // OpenCV for image processing
    implementation("org.openpnp:opencv:4.9.0-0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.60.0"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Configure bootRun to pass environment variables
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Pass all environment variables from the parent process
    environment(System.getenv())
}
