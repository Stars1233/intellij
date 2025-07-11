#
# Description: Blaze plugin for various IntelliJ products.
#
load(
    "//:build-visibility.bzl",
    "BAZEL_PLUGIN_SUBPACKAGES",
    "create_plugin_packages_group",
)

licenses(["notice"])

create_plugin_packages_group()

# Changelog file
filegroup(
    name = "changelog",
    srcs = ["CHANGELOG"],
    visibility = BAZEL_PLUGIN_SUBPACKAGES,
)

# BEGIN-EXTERNAL
# IJwB tests, run with an IntelliJ plugin SDK
test_suite(
    name = "ijwb_common_tests",
    tests = [
        "//base:integration_tests",
        "//base:unit_tests",
        "//dart:unit_tests",
        "//ijwb:headless_tests",
        "//ijwb:integration_tests",
        "//ijwb:unit_tests",
        "//java:integration_tests",
        "//java:unit_tests",
        "//kotlin:integration_tests",
        "//kotlin:unit_tests",
        "//plugin_dev:integration_tests",
        "//scala:integration_tests",
        "//scala:unit_tests",
        "//skylark:unit_tests",
    ],
)

# CE-compatible IJwB tests
# UE supports python as well, but we use python-ce.jar for integration tests.
# Since 2019.3, python-ce.jar needs 'com.intellij.modules.python-core-capable',
# but UE only has 'com.intellij.modules.python-pro-capable' instead.
test_suite(
    name = "ijwb_ce_tests",
    tests = [
        ":ijwb_common_tests",
        "//python:integration_tests",
        "//python:unit_tests",
    ],
)

# UE-compatible IJwB tests
test_suite(
    name = "ijwb_ue_tests",
    tests = [
        ":ijwb_common_tests",
        "//gazelle:integration_tests",
        "//golang:integration_tests",
        "//golang:unit_tests",
        "//javascript:integration_tests",
        "//javascript:unit_tests",
    ],
)

# CLwB tests, run with a CLion plugin SDK
test_suite(
    name = "clwb_tests",
    tests = [
        "//base:unit_tests",
        "//clwb:headless_tests",
        "//clwb:integration_tests",
        "//clwb:unit_tests",
        "//cpp:unit_tests",
        "//dart:unit_tests",
        "//python:unit_tests",
        "//skylark:unit_tests",
    ],
)
# END-EXTERNAL
