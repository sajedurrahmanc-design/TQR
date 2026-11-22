# APK বানানোর নিয়ম (How to Build Installable .apk)

এই ZIP ফাইলে সম্পূর্ণ Android প্রজেক্টের কোড যুক্ত করে দেওয়া হয়েছে। একটি **ইনস্টলযোগ্য .apk ফাইল** তৈরি করতে নিচের যেকোনো একটি উপায় অনুসরণ করুন:

## উপায় ১: Android Studio দিয়ে (সবচেয়ে সহজ)
1. ZIP ফাইলটি আনজিপ করুন।
2. **Android Studio** খুলে **Open** সিলেক্ট করে আনজিপ করা ফোল্ডারটি খুলুন।
3. Gradle Sync শেষ হওয়ার পর উপরের মেনু থেকে **Build > Build Bundle(s) / APK(s) > Build APK(s)** ক্লিক করুন।
4. বিল্ড শেষ হলে `app/build/outputs/apk/debug/app-debug.apk` ফাইলটি পাবেন—এটি ফোনে কপি করে সরাসরি ইনস্টল করতে পারবেন।

## উপায় ২: GitHub Actions দিয়ে (পিসিতে Android Studio না থাকলেও)
1. একটি নতুন GitHub Repository তৈরি করে এই ফাইলগুলো পুশ করুন।
2. `.github/workflows/build-apk.yml` ফাইলে `./gradlew assembleDebug` চালিয়ে Artifacts থেকে `app-debug.apk` ডাউনলোড করে ফোনে ইনস্টল করুন।
