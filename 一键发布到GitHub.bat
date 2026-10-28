@echo off
chcp 65001 >nul
title 发布课表 App 到 GitHub
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\publish-github.ps1"
echo.
pause
