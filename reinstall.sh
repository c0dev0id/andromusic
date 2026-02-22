#!/bin/sh
# reinstall.sh - Downloads the latest successful build artifact and reinstalls the app via adb.

REPO="c0dev0id/andromusic"
WORKFLOW="build.yml"
ARTIFACT_NAME="app-debug"
APK_FILE="app-debug.apk"
PACKAGE_ID="de.codevoid.andromusic"
WORK_DIR=$(mktemp -d)

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

echo "Finding latest successful build run..."
RUN_ID=$(gh run list \
    --repo "$REPO" \
    --workflow "$WORKFLOW" \
    --status success \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId')

if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; then
    echo "Error: No successful workflow runs found." >&2
    exit 1
fi

echo "Downloading artifact from run $RUN_ID..."
gh run download \
    --repo "$REPO" \
    --name "$ARTIFACT_NAME" \
    --dir "$WORK_DIR" \
    "$RUN_ID"

if [ ! -f "$WORK_DIR/$APK_FILE" ]; then
    echo "Error: APK file not found after download." >&2
    exit 1
fi

echo "Uninstalling old version..."
adb uninstall "$PACKAGE_ID" || true

echo "Installing new version..."
adb install "$WORK_DIR/$APK_FILE"

echo "Done."
