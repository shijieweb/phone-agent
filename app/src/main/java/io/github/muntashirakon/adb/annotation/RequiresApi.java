// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明最低 API 级别（值为 Build.VERSION_CODES 常量）。
 *
 * <p>本地空实现：vendoring libadb 时它原本依赖 androidx.annotation，本项目要求
 * 零第三方运行时依赖，因此只保留注解名与参数形状，不带任何运行期行为。
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.CONSTRUCTOR, ElementType.ANNOTATION_TYPE})
public @interface RequiresApi {
    int value();
}
