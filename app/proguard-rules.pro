# Darcha — R8 rules for the release build (T26)
#
# This file is intentionally almost empty, and that is the interesting part.
#
# AGP's `proguard-android-optimize.txt` plus the consumer rules that ship with
# AndroidX are enough for the whole app: **no keep rules of our own are
# required.** That is a property of how the project is built rather than luck:
#
#   * `:core:model` and `:core:parser` are pure JVM Kotlin with no reflection,
#     no serialization framework, and no runtime annotations. Every call site is
#     statically resolvable, so R8 can see the whole graph.
#   * The one reflective-looking call, `XmlPullParserFactory.newInstance()` in
#     `Xml.kt`, resolves on Android to a framework class that is not in the APK
#     at all — R8 cannot strip what it does not package.
#   * Compose, Lifecycle and DataStore bring their own consumer rules.
#
# Verified by running the shrunk build on a device, not by assuming: see
# docs/PERF.md "The shrunk build was then run, not just measured".
#
# ONE BEHAVIOUR WORTH KNOWING BEFORE YOU DEBUG A RELEASE BUILD:
# `proguard-android-optimize.txt` strips `Log.d` and `Log.v` via
# `-assumenosideeffects`. Every diagnostic this project measures with is a
# `Log.d`, so **none of them exist in a release build** — performance work has to
# be done on the debug build, and a release build verified by what is on screen.
#
# If you ever do need a keep rule here, write down what broke without it. An
# unexplained keep rule is indistinguishable from a superstition.
