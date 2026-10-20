#!/usr/bin/env sh
# Compiles and runs tests + demo. Works with a full JDK (javac) or a JRE that ships the jdk.compiler module.
set -e
rm -rf out && mkdir out
if command -v javac >/dev/null 2>&1; then JAVAC="javac --release 17"; else JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17"; fi
$JAVAC -d out $(find src -name '*.java')
java -cp out com.meridian.reliability.SelfTest
java -cp out com.meridian.reliability.DashboardTest
echo
java -cp out com.meridian.reliability.Main "$@"
