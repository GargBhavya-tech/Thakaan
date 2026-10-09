file(REMOVE_RECURSE "${INSTALL_DIR}" "${BUILD_DIR}/consumer-build")

function(run_checked)
    execute_process(COMMAND ${ARGV} RESULT_VARIABLE RESULT)
    if(NOT RESULT EQUAL 0)
        message(FATAL_ERROR "command failed with exit code ${RESULT}: ${ARGV}")
    endif()
endfunction()

run_checked("${CMAKE_COMMAND}" --install "${BUILD_DIR}" --config "${CONFIG}"
            --prefix "${INSTALL_DIR}")
if(SYSTEM_NAME STREQUAL "Linux" AND
   NOT EXISTS "${INSTALL_DIR}/${INSTALL_LIBDIR}/liblwbgt.so.0")
    message(FATAL_ERROR "installed library is missing the preserved SONAME")
endif()
foreach(DOCUMENT LICENSE ABI.md INPUTS.md NOTICE
        LicenseRef-UChicago-Argonne-WBGT-1.1.txt)
    if(NOT EXISTS "${INSTALL_DIR}/${INSTALL_DATADIR}/doc/lwbgt/${DOCUMENT}")
        message(FATAL_ERROR "installed documentation is missing ${DOCUMENT}")
    endif()
endforeach()
run_checked("${CMAKE_COMMAND}" -G "${GENERATOR}"
            -S "${SOURCE_DIR}" -B "${BUILD_DIR}/consumer-build"
            "-DCMAKE_PREFIX_PATH=${INSTALL_DIR}")
run_checked("${CMAKE_COMMAND}" --build "${BUILD_DIR}/consumer-build" --config "${CONFIG}")
if(SYSTEM_NAME STREQUAL "Windows")
    set(ENV{PATH} "${INSTALL_DIR}/${INSTALL_BINDIR};$ENV{PATH}")
elseif(SYSTEM_NAME STREQUAL "Darwin")
    set(ENV{DYLD_LIBRARY_PATH} "${INSTALL_DIR}/${INSTALL_LIBDIR}")
else()
    set(ENV{LD_LIBRARY_PATH} "${INSTALL_DIR}/${INSTALL_LIBDIR}")
endif()
foreach(NAME lwbgt_static_consumer lwbgt_shared_consumer lwbgt_cpp_consumer)
    set(CONSUMER "${BUILD_DIR}/consumer-build/${NAME}${EXECUTABLE_SUFFIX}")
    if(NOT EXISTS "${CONSUMER}" AND CONFIG)
        set(CONSUMER "${BUILD_DIR}/consumer-build/${CONFIG}/${NAME}${EXECUTABLE_SUFFIX}")
    endif()
    run_checked("${CONSUMER}")
endforeach()
