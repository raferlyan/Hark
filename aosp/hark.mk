# 在产品 mk 里：$(call inherit-product, vendor/raferlyan/hark/hark.mk)
HARK_DIR := $(patsubst %/,%,$(dir $(lastword $(MAKEFILE_LIST))))

PRODUCT_PACKAGES += Hark

# 模型放到 /product/etc/hark/models，目录结构和 sdcard 上一样（tools/push_models.sh 推的那套）。
# 只放要用的：唤醒模型 + 默认普通话流式模型约 200 MB；方言/SenseVoice/声纹按需加。
HARK_MODELS ?= $(HARK_DIR)/models
PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,$(HARK_MODELS),$(TARGET_COPY_OUT_PRODUCT)/etc/hark/models)
