@echo off
set BASE_URL=%~1
if "%BASE_URL%"=="" set BASE_URL=http://localhost:8080

echo Running on-sale stampede burst benchmark against: %BASE_URL%
python burst.py %BASE_URL%
