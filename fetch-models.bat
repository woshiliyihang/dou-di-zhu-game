@echo off
rem 英→中模型一键下载。双击就行，跑完看窗口最后一行提示。
cd /d "%~dp0"
chcp 65001 >nul
where python >nul 2>nul
if %errorlevel%==0 (
    python tools\fetch_en_zh.py %*
) else (
    py -3 tools\fetch_en_zh.py %*
)
echo.
pause
