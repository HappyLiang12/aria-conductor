#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/agent-control-tower"

# Prerequisites check
check_command() {
    if ! command -v "$1" &> /dev/null; then
        echo "ERROR: $1 is not installed. $2"
        exit 1
    fi
}

check_command "java" "Install JDK 21: https://adoptium.net/"
check_command "mvn" "Install Maven 3.9+: https://maven.apache.org/"

java -version 2>&1 | grep -q "21" || echo "WARNING: JDK 21 recommended. Current version may not be compatible."

echo "Starting Aria Conductor backend..."
cd "$BACKEND_DIR"

PROFILE="${SPRING_PROFILES_ACTIVE:-h2}"
echo "  Profile: $PROFILE"
echo "  Port: 8080"

if [ ! -f "act-app/target/act-app-0.1.0-SNAPSHOT.jar" ]; then
    echo "Building..."
    mvn clean install -DskipTests -B
fi

echo "Launching Spring Boot..."
mvn spring-boot:run -pl act-app \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.jvmArguments="--enable-preview"