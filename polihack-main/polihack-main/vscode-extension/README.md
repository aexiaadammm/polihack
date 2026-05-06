# Hive VS Code Extension

Local MVP for running Hive repository analysis from Visual Studio Code.

## Run locally

1. Start the Spring Boot backend separately on `http://localhost:8080`.
2. Open this folder in VS Code.
3. Run `npm install`.
4. Press `F5` to launch an Extension Development Host.
5. Run `Hive: Analyze Current Repository` from the Command Palette.

The extension detects the current Git remote and branch, sends them to the backend, renders generated questions, generates the orchestrator, and can save it as Markdown in the workspace.
