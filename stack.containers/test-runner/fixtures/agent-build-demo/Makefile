CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra -std=c11
BUILD_DIR := build
TARGET := $(BUILD_DIR)/agent-build-demo

all: $(TARGET)

$(TARGET): main.c
	mkdir -p $(BUILD_DIR)
	$(CC) $(CFLAGS) -o $(TARGET) main.c

run: $(TARGET)
	./$(TARGET)

clean:
	rm -rf $(BUILD_DIR)
