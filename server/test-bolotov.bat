@echo off
set TESSDATA_PREFIX=C:\Projects\bookworm\bookworm\tessdata
gradlew.bat test --tests BolotovMetadataExtractionTest
