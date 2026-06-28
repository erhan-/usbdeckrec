cmake_minimum_required(VERSION 3.22.1)
project("deckrec_audio")

# Oboe library via Prefab (Google-recommended prebuilt AAR)
find_package(oboe REQUIRED CONFIG)

# Audio engine sources
add_library(deckrec_audio SHARED
    NativeBridge.cpp
    AudioCaptureEngine.cpp
    UsbIsochronousCaptureEngine.cpp
    DjmBulkCaptureEngine.cpp
    ChannelExtractor.cpp
    LockFreeQueue.cpp
)

target_include_directories(deckrec_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(deckrec_audio
    oboe::oboe
    log
)
