# 📱 Pro Media Downloader (Android)

Kotlin ve Jetpack Compose ile geliştirilmiş, **yt-dlp** ve **FFmpeg** destekli modern, hızlı ve açık kaynaklı Android medya indirme uygulaması.

## ✨ Özellikler

* **Geniş Platform Desteği:** Desteklenen ve medya indirme iznine sahip olan birçok platformdan video ve ses indirme.
* **Hızlı Paylaşım Menüsü (Share Sheet):** Diğer uygulamalarda **Paylaş → Pro Media Downloader** seçeneğine dokunarak uygulamayı tam ekran açmadan, küçük bir pencere üzerinden tek tıkla video veya MP3 indirme.
* **Arka Planda Kesintisiz İndirme:** Ön plan servisi (Foreground Service) sayesinde uygulamadan çıksanız veya ekranı kilitleseniz bile indirme devam eder. İlerleme bildirim çubuğundan takip edilebilir ve iptal edilebilir.
* **Gelişmiş Kodek Seçimi:** Varsayılan **H.264** desteğinin yanı sıra **HEVC (H.265)**, **VP9** ve **AV1** (4K desteği) seçenekleri. Seçilen kodek mevcut değilse en yakın uyumlu formatı otomatik seçme.
* **Esnek Kayıt Konumu:** İstenilen klasörü seçme ve hatırlama özelliği (Varsayılan: `İndirilenler/ProMediaDownloader`).
* **Uyarlanabilir Modern Arayüz:** Telefonlarda tek sütun, tablet ve yatay ekranlarda iki sütunlu tasarım; Koyu (Dark) ve Açık (Light) tema desteği.

## 📥 Kurulum (Kullanıcılar İçin)

1. Sağ taraftaki **[Releases](../../releases)** bölümüne gidin.
2. En son sürüme ait `.apk` dosyasını telefonunuza indirin.
3. İndirilen dosyaya dokunun (Gerekirse tarayıcınız için *"Bilinmeyen kaynaklardan uygulama yüklemeye izin ver"* seçeneğini onaylayın).

> **💡 İpucu:** Android 11 ve üzeri sürümlerde sistem kısıtlamaları nedeniyle doğrudan ana "İndirilenler" klasörü seçilemeyebilir. Bu durumda İndirilenler içinde yeni bir alt klasör oluşturup seçmeniz önerilir.

## 🛠️ Geliştiriciler İçin Derleme

Projeyi kaynak koddan derlemek için:

1. Bu depoyu bilgisayarınıza klonlayın veya ZIP olarak indirin.
2. **Android Studio**'yu açın ve **Open** seçeneği ile proje klasörünü seçin.
3. Gradle senkronizasyonunun (**Sync Now**) tamamlanmasını bekleyin.
4. Fiziksel bir Android cihaz bağlayın veya emülatör başlatın ve **Run ▶** butonuna basın.

## 🧰 Kullanılan Teknolojiler

* **Dil:** Kotlin
* **Arayüz:** Jetpack Compose (Material 3)
* **İndirme Motoru:** [youtubedl-android](https://github.com) (yt-dlp + FFmpeg)
* **Mimari:** MVVM (ViewModel, Foreground Service)

## 👨‍💻 Geliştirici

* **Nebi Asadli**

## 📄 Lisans

Bu proje **MIT Lisansı** altında açık kaynak olarak sunulmaktadır. Detaylar için `LICENSE` dosyasına göz atabilirsiniz.
## ⚠️ Yasal Uyarı (Disclaimer)

Bu uygulama yalnızca **eğitim amaçlı** ve kullanıcıların telif hakkı bulunmayan, açık lisanslı (Creative Commons vb.) veya kendi ürettikleri medyaları indirmeleri için geliştirilmiştir.

Geliştirici, bu yazılımın üçüncü taraf platformların kullanım şartlarına aykırı olarak kullanılmasından veya telif hakkıyla korunan içeriklerin izinsiz indirilmesinden hiçbir şekilde sorumlu tutulamaz. Kullanıcılar, bulundukları ülkenin telif hakkı yasalarına ve ilgili platformların hizmet şartlarına uymakla yükümlüdür.
