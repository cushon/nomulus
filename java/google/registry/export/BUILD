package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0


java_library(
    name = "export",
    srcs = glob(["*.java"]),
    deps = [
        "//apiserving/discoverydata/bigquery:bigqueryv2",
        "//apiserving/discoverydata/drive",
        "//java/com/google/api/client/extensions/appengine/http",
        "//java/com/google/api/client/googleapis/extensions/appengine/auth/oauth2",
        "//java/com/google/api/client/googleapis/json",
        "//java/com/google/api/client/http",
        "//java/com/google/api/client/json",
        "//java/com/google/api/client/json/jackson2",
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/html",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/appengine_gcs_client",
        "//third_party/java/appengine_mapreduce2:appengine_mapreduce",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/json_simple",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/servlet/servlet_api",
        "//java/google/registry/bigquery",
        "//java/google/registry/config",
        "//java/google/registry/gcs",
        "//java/google/registry/groups",
        "//java/google/registry/mapreduce",
        "//java/google/registry/mapreduce/inputs",
        "//java/google/registry/model",
        "//java/google/registry/request",
        "//java/google/registry/storage/drive",
        "//java/google/registry/util",
    ],
)