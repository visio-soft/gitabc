#!/bin/bash

# GitABC Demo Script
# This script demonstrates the Git client's capabilities

set -e

echo "========================================="
echo "GitABC - Multiplatform Git Client Demo"
echo "========================================="
echo ""

# 1. Check if the project builds
echo "Step 1: Building the project..."
./gradlew build --no-daemon --quiet
echo "✓ Build successful!"
echo ""

# 2. Run core functionality test
echo "Step 2: Running core functionality tests..."
./gradlew runCoreTest --no-daemon --quiet
echo "✓ Core tests passed!"
echo ""

# 3. Create distribution package
echo "Step 3: Creating distribution package..."
./gradlew packageUberJarForCurrentOS --no-daemon --quiet
echo "✓ Distribution package created!"
echo ""

# 4. Show package info
echo "Step 4: Package information..."
JAR_FILE=$(find build/compose/jars -name "*.jar" -type f | head -1)
if [ -f "$JAR_FILE" ]; then
    echo "Package: $JAR_FILE"
    echo "Size: $(du -h "$JAR_FILE" | cut -f1)"
    echo ""
    echo "To run the application:"
    echo "  java -jar $JAR_FILE"
else
    echo "Package file not found!"
    exit 1
fi
echo ""

echo "========================================="
echo "Demo completed successfully!"
echo "========================================="
echo ""
echo "Features implemented:"
echo "  ✓ Multiplatform support (macOS & Linux)"
echo "  ✓ Multi-repository browser"
echo "  ✓ Directory-based file change tracking"
echo "  ✓ Branch switching"
echo "  ✓ Commit status (ahead/behind)"
echo "  ✓ Changelist management"
echo "  ✓ All UI and code in English"
echo ""
