"""Convenience methods for plugin_api."""

load("@rules_java//java:defs.bzl", "java_import")

# The current indirect ij_product mapping (eg. "intellij-latest")
INDIRECT_IJ_PRODUCTS = {
    "intellij-oss-oldest-stable": "intellij-2025.1",
    "intellij-oss-latest-stable": "intellij-2025.2",
    "intellij-oss-under-dev": "intellij-2025.2",
    "intellij-ue-oss-oldest-stable": "intellij-ue-2025.1",
    "intellij-ue-oss-latest-stable": "intellij-ue-2025.2",
    "intellij-ue-oss-under-dev": "intellij-ue-2025.2",
    "clion-oss-oldest-stable": "clion-2025.1",
    "clion-oss-latest-stable": "clion-2025.2",
    "clion-oss-under-dev": "clion-2025.2",
}

(CHANNEL_STABLE, CHANNEL_BETA, CHANNEL_CANARY, CHANNEL_FREEFORM) = ("stable", "beta", "canary", "freeform")

INDIRECT_PRODUCT_CHANNELS = {
    # Channel mapping for Bazel Plugin OSS
    "intellij-oss-oldest-stable": CHANNEL_STABLE,
    "intellij-oss-latest-stable": CHANNEL_STABLE,
    "intellij-oss-under-dev": CHANNEL_CANARY,
    "intellij-ue-oss-oldest-stable": CHANNEL_STABLE,
    "intellij-ue-oss-latest-stable": CHANNEL_STABLE,
    "intellij-ue-oss-under-dev": CHANNEL_CANARY,
    "clion-oss-oldest-stable": CHANNEL_STABLE,
    "clion-oss-latest-stable": CHANNEL_STABLE,
    "clion-oss-under-dev": CHANNEL_CANARY,
}

def _check_channel_map():
    if INDIRECT_PRODUCT_CHANNELS.keys() != INDIRECT_IJ_PRODUCTS.keys():
        fail("Key mismatch between INDIRECT_PRODUCT_CHANNELS and INDIRECT_IJ_PRODUCTS: missing: %s extra: %s" % (
            [k for k in INDIRECT_IJ_PRODUCTS.keys() if k not in INDIRECT_PRODUCT_CHANNELS.keys()],
            [k for k in INDIRECT_PRODUCT_CHANNELS.keys() if k not in INDIRECT_IJ_PRODUCTS.keys()],
        ))
    unexpected = [
        (k, v)
        for k, v in INDIRECT_PRODUCT_CHANNELS.items()
        if v not in [CHANNEL_STABLE, CHANNEL_BETA, CHANNEL_CANARY]
    ]
    if unexpected:
        fail("Unexpected values in INDIRECT_PRODUCT_CHANNELS: %s" % unexpected)

def _build_ij_product(ide, directory, version):
    return struct(
        ide = ide,
        directory = "%s_%s" % (directory, version.replace(".", "_")),
        version = version,
    )

def _build_ij_product_dict(versions):
    result = {}
    for version in versions:
        result["intellij-%s" % version] = _build_ij_product("intellij", "intellij_ce", version)
        result["intellij-ue-%s" % version] = _build_ij_product("intellij-ue", "intellij_ue", version)
        result["clion-%s" % version] = _build_ij_product("clion", "clion", version)

    return result

DIRECT_IJ_PRODUCTS = _build_ij_product_dict(["2025.1", "2025.2"])

def select_for_plugin_api(params):
    """Selects for a plugin_api.

    Args:
        params: A dict with ij_product -> value.
                You may only include direct ij_products here,
                not indirects (eg. intellij-latest).
    Returns:
        A select statement on all plugin_apis. Unless you include a "default",
        a non-matched plugin_api will result in an error.

    Example:
      java_library(
        name = "foo",
        srcs = select_for_plugin_api({
            "intellij-2016.3.1": [...my intellij 2016.3 sources ....],
            "intellij-2012.2.4": [...my intellij 2016.2 sources ...],
        }),
      )
    """
    for indirect_ij_product in INDIRECT_IJ_PRODUCTS:
        if indirect_ij_product in params:
            error_message = "".join([
                "Do not select on indirect ij_product %s. " % indirect_ij_product,
                "Instead, select on an exact ij_product.",
            ])
            fail(error_message)
    return _do_select_for_plugin_api(params)

def _do_select_for_plugin_api(params):
    """A version of select_for_plugin_api which accepts indirect products."""
    if not params:
        fail("Empty select_for_plugin_api")

    expanded_params = dict(**params)

    # Expand all indirect plugin_apis to point to their
    # corresponding direct plugin_api.
    #
    # {"intellij-2016.3.1": "foo"} ->
    # {"intellij-2016.3.1": "foo", "intellij-latest": "foo"}
    fallback_value = None
    for indirect_ij_product, resolved_plugin_api in INDIRECT_IJ_PRODUCTS.items():
        if resolved_plugin_api in params:
            expanded_params[indirect_ij_product] = params[resolved_plugin_api]
            if not fallback_value:
                fallback_value = params[resolved_plugin_api]
        if indirect_ij_product in params:
            expanded_params[resolved_plugin_api] = params[indirect_ij_product]

    # Map the shorthand ij_products to full config_setting targets.
    # This makes it more convenient so the user doesn't have to
    # fully specify the path to the plugin_apis
    select_params = dict()
    for ij_product, value in expanded_params.items():
        if ij_product == "default":
            select_params["//conditions:default"] = value
        else:
            select_params["//intellij_platform_sdk:" + ij_product] = value

    return select(
        select_params,
        no_match_error = "define an intellij product version, e.g. --define=ij_product=intellij-latest",
    )

def select_for_ide(intellij = None, intellij_ue = None, clion = None, default = []):
    """Selects for the supported IDEs.

    Args:
        intellij: Files to use for IntelliJ. If None, will use default.
        intellij_ue: Files to use for IntelliJ UE. If None, will use value chosen for 'intellij'.
        clion: Files to use for CLion. If None will use default.
        default: Files to use for any IDEs not passed.
    Returns:
        A select statement on all plugin_apis to lists of files, sorted into IDEs.

    Example:
      java_library(
        name = "foo",
        srcs = select_for_ide(
            clion = [":cpp_only_sources"],
            default = [":java_only_sources"],
        ),
      )
    """
    intellij = intellij if intellij != None else default
    intellij_ue = intellij_ue if intellij_ue != None else intellij
    clion = clion if clion != None else default

    ide_to_value = {
        "intellij": intellij,
        "intellij-ue": intellij_ue,
        "clion": clion,
    }

    # Map (direct ij_product) -> corresponding ide value
    params = dict()
    for ij_product, value in DIRECT_IJ_PRODUCTS.items():
        params[ij_product] = ide_to_value[value.ide]
    params["default"] = default

    return select_for_plugin_api(params)

def select_for_version(versions, default = []):
    """Selects for the supported IDEs.

    Args:
        version: Map from version to File list.
        default: Files to use for any version not passed.
    Returns:
        A select statement on all versions to select a list of files.

    Example:
      java_library(
        name = "foo",
        srcs = select_for_version(
            versions = { "2024.1": [":2024_1_only_sources"] },
            default = [":default_sources"],
        ),
      )
    """

    params = dict()
    for ij_product, value in DIRECT_IJ_PRODUCTS.items():
        if value.version in versions:
            params[ij_product] = versions[value.version]
    params["default"] = default

    return select_for_plugin_api(params)

def _plugin_api_directory(value):
    if hasattr(value, "oss_workspace"):
        directory = value.oss_workspace
    else:
        directory = value.directory
    return "@" + directory + "//"

def select_from_plugin_api_directory(intellij, clion, intellij_ue = None):
    """Internal convenience method to generate select statement from the IDE's plugin_api directories.

    Args:
      intellij: the items that IntelliJ product to use.
      clion: the items that clion product to use.
      intellij_ue: the items that intellij ue product to use.

    Returns:
      a select statement map from DIRECT_IJ_PRODUCTS to items that they should use.

    """

    ide_to_value = {
        "intellij": intellij,
        "intellij-ue": intellij_ue if intellij_ue else intellij,
        "clion": clion,
    }

    # Map (direct ij_product) -> corresponding product directory
    params = dict()
    for ij_product, value in DIRECT_IJ_PRODUCTS.items():
        params[ij_product] = [_plugin_api_directory(value) + item for item in ide_to_value[value.ide]]

    # No ij_product == intellij-latest
    params["default"] = params[INDIRECT_IJ_PRODUCTS["intellij-oss-latest-stable"]]

    return select_for_plugin_api(params)

def select_from_plugin_api_version_directory(params):
    """Selects for a plugin_api direct version based on its directory.

    Args:
        params: A dict with ij_product -> value.
                You may only include direct ij_products here,
                not indirects (eg. intellij-latest).
    Returns:
        A select statement on all plugin_apis. Unless you include a "default",
        a non-matched plugin_api will result in an error.
    """
    for indirect_ij_product in INDIRECT_IJ_PRODUCTS:
        if indirect_ij_product in params:
            error_message = "".join([
                "Do not select on indirect ij_product %s. " % indirect_ij_product,
                "Instead, select on an exact ij_product.",
            ])
            fail(error_message)

    # Map (direct ij_product) -> corresponding value relative to product directory
    for ij_product, value in params.items():
        if ij_product != "default":
            params[ij_product] = [_plugin_api_directory(DIRECT_IJ_PRODUCTS[ij_product]) + item for item in value]

    return _do_select_for_plugin_api(params)

def get_versions_to_build(product):
    """"Returns a set of unique product version aliases to test and build during regular release process.

    For each product, we care about four versions aliases to build and release to JetBrains
    repository; -latest, -beta, -oss-oldest-stable and oss-latest-stable.
    However, some of these aliases can point to the same IDE version and this can lead
    to conflicts if we attempt to blindly build and upload the four versions.
    This function is used to return only the aliases that point to different
    IDE versions of the given product.

    Args:
        product: name of the product; android-studio, clion, intellij-ue

    Returns:
        A space separated list of product version aliases to build, the values can be
        oss-oldest-stable, oss-latest-stable, internal-stable and internal-beta.
    """
    aliases_to_build = []
    plugin_api_versions = []
    for alias in ["oss-oldest-stable", "latest", "oss-latest-stable", "beta"]:
        indirect_ij_product = product + "-" + alias
        if indirect_ij_product not in INDIRECT_IJ_PRODUCTS:
            fail(
                "Product-version alias %s not found." % indirect_ij_product,
                "Invalid product: %s only android-studio, clion and intellij-ue are accepted." % product,
            )

        version = INDIRECT_IJ_PRODUCTS[indirect_ij_product]
        if version not in plugin_api_versions:
            plugin_api_versions.append(version)
            if alias == "latest":
                aliases_to_build.append("internal-stable")
            elif alias == "beta":
                aliases_to_build.append("internal-beta")
            else:
                aliases_to_build.append(alias)

    return " ".join(aliases_to_build)

def get_unique_supported_oss_ide_versions(product):
    """"Returns the unique supported IDE versions for the given product in the OSS Bazel plugin

    Args:
        product: name of the product; android-studio, clion, intellij-ue

    Returns:
        A space separated list of the aliases of the unique IDE versions for the
        OSS Bazel plugin.
    """
    supported_versions = []
    unique_aliases = []
    for alias in ["oss-oldest-stable", "oss-latest-stable"]:
        indirect_ij_product = product + "-" + alias
        if indirect_ij_product not in INDIRECT_IJ_PRODUCTS:
            fail(
                "Product-version alias %s not found." % indirect_ij_product,
                "Invalid product: %s, only android-studio, clion and intellij-ue are accepted." % product,
            )
        ver = INDIRECT_IJ_PRODUCTS[indirect_ij_product]
        if ver not in supported_versions:
            supported_versions.append(ver)
            unique_aliases.append(alias)

    return " ".join(unique_aliases)

def no_mockito_extensions(name, jars, **kwargs):
    """Removes mockito extensions from jars.

    Args:
        name: Name of the resulting java_import target.
        jars: List of jars from which to remove mockito extensions.
        **kwargs: Arbitrary attributes for the java_import target.
    """

    output_jars = []
    for input_jar in jars:
        output_jar_name = name + "_" + input_jar.replace("/", "_")
        output_jar = name + "/" + input_jar
        native.genrule(
            name = output_jar_name,
            srcs = [input_jar],
            outs = [output_jar],
            cmd = """
            tmpdir=$$(mktemp -d)
            zipper="$$(pwd)/$(execpath @bazel_tools//tools/zip:zipper)"
            "$$zipper" x "$<" -d ".out"
            mv ".out" "$$tmpdir"

            pushd "$$tmpdir/.out" >/dev/null
            rm -fr "mockito-extensions"

            # We store the results from `find` in a file to deal with filenames with spaces
            files_to_tar_file=$$(mktemp)
            find . -type f | sed 's:^./::' > "$${files_to_tar_file}"

            "$$zipper" cC "../out.jar" "@$${files_to_tar_file}"
            popd

            cp "$$tmpdir/out.jar" "$@"
            chmod u+rw "$@"
            """,
            tools = ["@bazel_tools//tools/zip:zipper"],
        )
        output_jars.append(output_jar_name)
    java_import(
        name = name,
        jars = output_jars,
        **kwargs
    )

# Since 2022.3, JVM 17 is required to start IntelliJ
# https://blog.jetbrains.com/platform/2022/08/intellij-project-migrates-to-java-17/
def java_version_flags():
    return ["-source", "17", "-target", "17"]

def select_for_channel(channel_map):
    """Returns a select based on the IDE channel (stable, beta, canary).

    Args:
      channel_map: a dict with keys "stable", "beta" and "canary". The rest of targets will be considered "freeform"

    Returns:
      A select that will select values from channel_map based on the build config.
    """
    _check_channel_map()
    if channel_map.keys() != [CHANNEL_STABLE, CHANNEL_BETA, CHANNEL_CANARY, CHANNEL_FREEFORM]:
        fail("channel_map must contain exactly %s, %s and %s" % (CHANNEL_STABLE, CHANNEL_BETA, CHANNEL_CANARY, CHANNEL_FREEFORM))
    select_map = {
        ("//intellij_platform_sdk:%s" % indirect_product): channel_map[channel]
        for indirect_product, channel in INDIRECT_PRODUCT_CHANNELS.items()
    }

    # We reverse INDIRECT_IJ_PRODUCTS.items() here to that the inverse map contains
    # the first occurrence of any value that is duplicated, not the last:
    inverse_ij_products = {v: k for k, v in reversed(INDIRECT_IJ_PRODUCTS.items())}

    # Add directly specified IDE versions which some builds use:
    select_map.update(
        {
            ("//intellij_platform_sdk:%s" % direct_product): channel_map[INDIRECT_PRODUCT_CHANNELS[indirect_product]]
            for direct_product, indirect_product in inverse_ij_products.items()
        },
    )

    # Some IDE versions are not in a channel, but users would still like to build and test them:
    select_map.update(
        {
            ("//intellij_platform_sdk:%s" % direct_product): channel_map[CHANNEL_FREEFORM]
            for direct_product in DIRECT_IJ_PRODUCTS.keys()
            if direct_product not in inverse_ij_products
        },
    )

    select_map.update({"//conditions:default": channel_map[CHANNEL_STABLE]})

    return select(select_map)
