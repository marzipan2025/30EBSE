# OkHttp 이 참조하는 선택적 클래스들 — 없어도 동작한다.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# PdfBox-Android 는 JPEG2000 그림을 풀 때 별도 라이브러리(jp2-android)를 부른다.
# 글만 뽑으므로 넣지 않았다. 없는 클래스라며 R8 이 멈추지 않게 한다.
-dontwarn com.gemalto.jp2.**
# PdfBox 는 글꼴·인코딩 표를 이름으로 찾는다(리플렉션·리소스). 줄이지 않는다.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
