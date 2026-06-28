if(NOT TARGET oboe::oboe)
add_library(oboe::oboe SHARED IMPORTED)
set_target_properties(oboe::oboe PROPERTIES
    IMPORTED_LOCATION "/root/.gradle/caches/transforms-3/d4655d307257f534265a22ce0a073c04/transformed/oboe-1.9.0/prefab/modules/oboe/libs/android.x86_64/liboboe.so"
    INTERFACE_INCLUDE_DIRECTORIES "/root/.gradle/caches/transforms-3/d4655d307257f534265a22ce0a073c04/transformed/oboe-1.9.0/prefab/modules/oboe/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

