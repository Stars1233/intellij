load("@bazel_skylib//rules:build_test.bzl", "build_test")
load("//:build-visibility.bzl", "DEFAULT_TEST_VISIBILITY")

package(
    default_visibility = DEFAULT_TEST_VISIBILITY,
)

build_test(
    name = "test_projects_test",
    targets = [
        # TODO: b/335388630
        #        "//aswb/testdata/projects/android1/project:test_data_build",
        "//aswb/testdata/projects/autovalue:test_data_build",
        "//aswb/testdata/projects/cc1:test_data_build",
        "//aswb/testdata/projects/cc_genhdr:test_data_build",
        "//aswb/testdata/projects/generated1:test_data_build",
        "//aswb/testdata/projects/generated2:test_data_build",
        "//aswb/testdata/projects/generated3:test_data_build",
        "//aswb/testdata/projects/generated_tests:test_data_build",
        "//aswb/testdata/projects/javabinaries:test_data_build",
        "//aswb/testdata/projects/kotlin1:test_data_build",
        "//aswb/testdata/projects/sample1:test_data_build",
        "//aswb/testdata/projects/proto1/project:test_data_build",
        "//aswb/testdata/projects/src_transform:test_data_build",
    ],
)
