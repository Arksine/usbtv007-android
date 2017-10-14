cmake_minimum_required(VERSION 3.4.1)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11 -pthread -Wall -Werror")

add_library(usbtv SHARED
			usbtv.cpp
			UsbTvDriver.cpp
			usbtv_core.cpp
			AndroidUsbDevice.cpp)

# include libraries needed
target_link_libraries(usbtv
					  atomic
                      android
                      log)