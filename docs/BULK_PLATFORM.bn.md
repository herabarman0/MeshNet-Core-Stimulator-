# বাল্ক লিংক, হালকা প্রিসেট, সার্ভিস, CI

## ১. বড় ফাইল: Nearby / হটস্পট / Wi-Fi Direct (`BulkLink`)
**সমস্যা:** মেশ রেডিওতে বড় ফাইল ৪ KB চাঙ্কে ভেঙে যায়; Bluetooth-ক্লাস রেডিওতে ধীর।
**সমাধান:** সরাসরি প্রতিবেশীর (১ hop) জন্য আলাদা দ্রুত পথ। মাল্টি-hop আর ব্যর্থতায় আগের চাঙ্ক-পথই চলে।

- `BulkLink` (kit): `canSend / send / setReceiver`। ডেটা পাঠানোর আগেই **এন্ড-টু-এন্ড এনক্রিপ্টেড** (Ratchet), তাই লিংক প্লেইনটেক্সট দেখে না।
- `TcpBulk`: যেকোনো IP পথে (Wi-Fi Direct গ্রুপ, হটস্পট, LAN, USB টেদারিং, ডেস্কটপ)। `follow(tcpRadio, port)` দিলে TcpRadio-র লিংক ধরে ঠিকানা নিজে পায়। ২৪ MB সীমা, ভুয়া/বিশাল হেডার প্রত্যাখ্যান।
- `NearbyRadio` এখন `BulkLink`-ও: Nearby **FILE পেলোড** (Nearby নিজে Wi-Fi/হটস্পটে আপগ্রেড করে)।
- `CompositeBulk`: সবচেয়ে দ্রুতটা আগে, ব্যর্থ হলে পরেরটা।
- `MeshClient.builder(...).bulk(link, minBytes)` (ডিফল্ট ১৬ KB); `bulkAckTimeout(ms)` (ডিফল্ট ২০ সে.)।
- **নিরাপত্তা/ব্যর্থতা:** লিংক ব্যর্থ হলে → চাঙ্ক-পথ। হাতে পৌঁছালো কিন্তু রিসিপ্ট এল না (নষ্ট কপি ফেলে দিয়েছে) → সময়ের পরে চাঙ্ক-পথে আবার। নষ্ট বা ভুল-প্রেরকের পেলোড গ্রহীতা ফেলে দেয়।
- Android: `MeshNet.Options.bulk = true` (ডিফল্ট **বন্ধ**: আসল ফোনে চালানো হয়নি)।

**মাপা (লোকালহোস্ট, JVM):** ২ MB → ~০.২–০.৩ সেকেন্ড, মেশ রেডিওতে মাত্র ১০টা ফ্রেম (চাঙ্কিংয়ে ৫০০+ লাগত)। আসল Nearby/Wi-Fi গতি আমি মাপিনি; আপনার ফোনে মাপতে হবে।

## ২. হালকা ও মাল্টি-প্ল্যাটফর্ম
- `core` ও `kit` **শূন্য নির্ভরতা**, Java 8 বাইটকোড; শুধু `java.util/nio/security/javax.crypto`। JVM (ডেস্কটপ, সার্ভার, Raspberry Pi) ও Android একই কোড। `java.util.function` সরানো হয়েছে (পুরনো Android-এ API 24-এর নিচে ক্র্যাশ করত)।
- আকার (`sh tools/size-report.sh`): core ≈ ২৬৫ KB, kit ≈ ২৯৩ KB (jar, ডিবাগ তথ্য ছাড়া)। R8 নিয়ম: `android-kit/consumer-rules.pro`।
- `Presets.lite()`: কম RAM-এর ডিভাইসের জন্য ছোট ক্যাশ (dedup ২৫৬, স্টোর ৪৮, রুট ২৪, ডিরেক্টরি ৫১২, একসাথে ৪টা বড় ট্রান্সফার, ২ MB বাফার)। সীমা: মেশের ওপর দিয়ে ~২ MB-র বেশি ফাইল নয় (বাল্ক লিংক ছাড়া)। `Presets.both(a, b)` দিয়ে জোড়া।
- **iOS: এখনো নেই।** Swift/Kotlin Multiplatform বা C++ পোর্ট লাগবে; `native/cpp` আংশিক। এটা আলাদা বড় কাজ, আমি এখানে করিনি।

## ৩. ব্যাকগ্রাউন্ড সার্ভিস (`MeshService`, Android)
Foreground service + notification + partial wake lock + `START_STICKY`। ম্যানিফেস্টে `foregroundServiceType="connectedDevice"` ও অনুমতিগুলো (ফাইলের ডক-কমেন্টে)। ব্যাটারি-অপটিমাইজেশন ছাড়ের ইনটেন্ট: `MeshService.batteryExemptionIntent`। **আসল ফোনে চালানো হয়নি;** Xiaomi/Huawei/Samsung আলাদা করে মেরে ফেলতে পারে।

## ৪. ফিল্ড-টেস্ট সহায়ক
`client.diagnostics()`: নোড, প্রতিবেশী ও লিংক-মান (RTT/লস), আউটবক্স, কাউন্টার, বাল্ক ব্যবহার। **মেসেজের বিষয়বস্তু, কী বা নাম থাকে না**; বাগ রিপোর্টে সরাসরি পাঠানো যায়।

## ৫. CI
`.github/workflows/ci.yml`: প্রতি push-এ `tools/build-and-test.sh` (সব টেস্ট) + Gradle (`:core:test :sim:test :kit:build`)। গিটহাবে প্রথমবার চালিয়ে দেখুন; Gradle অংশ আমি এখানে চালাতে পারিনি।
