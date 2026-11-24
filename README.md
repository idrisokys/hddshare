# Kopya

macOS için TeraCopy benzeri, tek işlevli hızlı ve güvenilir dosya kopyalama uygulaması.
Dosya veya klasörleri sürükle, hedefi seç, kopyala. İlerleme çubuğu hız ve kalan süreyi gösterir.

**Gereksinimler:** Apple Silicon (M-serisi, M4 ve üstü için ayarlı) ve macOS 15 veya üstü.

## Özellikler

- **Paralel kopyalama:** Dosyalar 32 MB'lık parçalara bölünür, birden çok iş parçacığı aynı anda okuyup yazar (NVMe için).
- **Otomatik ayar:** Kopya sırasında hız ölçülür; paralel akış sayısı ve blok boyutu (4/8/16 MB) o diske göre canlı ayarlanır. Bulunan ayar disk çifti için hatırlanır. Ayar yalnızca ~2 GB ve üstü işlerde devreye girer.
- **Anında klonlama:** Aynı APFS biriminde `clonefile` ile kopya saniyeler içinde biter.
- **Güvenli yazma:** Dosyalar önce `.kopya-part` adıyla yazılır, bitince atomik olarak yeniden adlandırılır. Yarım dosya hedefte görünmez.
- **Kalıcı yazma:** 256 MB üstü dosyalarda dosya başına `F_FULLFSYNC`, tüm işlem sonunda hedef klasör için bir kez daha `F_FULLFSYNC`.
- **Metadata:** İzinler, zaman damgaları, xattr ve ACL korunur; sembolik bağlantılar bağlantı olarak kopyalanır.
- **Çakışma seçenekleri:** İkisini de tut, üzerine yaz, atla.
- **Tam hız:** App Nap ve uyku engellenir, disk G/Ç önceliği yüksek ayarlanır.
- **Modlar:** Otomatik (önerilen), SSD (sabit, tüm çekirdekler), HDD (sıralı).

## Derleme

Command Line Tools gerekir (`xcode-select --install`).

```bash
./build.sh
```

`Kopya.app` aynı klasörde oluşur.

## GitHub Actions

`.github/workflows/build.yml` her push'ta uygulamayı `macos-15` üzerinde derler ve `Kopya-macos-arm64.zip`
dosyasını Actions artifact olarak yükler. `v1.0.0` gibi bir etiket (tag) gönderirsen zip otomatik olarak
Releases sayfasına eklenir:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## İlk açılış

Uygulama imzasızdır (ad-hoc). İndirilen sürümde macOS engellerse:

```bash
xattr -cr Kopya.app
```

ya da uygulamaya sağ tıklayıp **Aç**'ı seç. Masaüstü, Belgeler veya harici disklere erişimde izin sorabilir;
gerekirse Sistem Ayarları → Gizlilik ve Güvenlik → Tam Disk Erişimi'nden Kopya'ya izin ver.

## Dosyalar

| Dosya | Açıklama |
| --- | --- |
| `Kopya.swift` | Uygulamanın tamamı (SwiftUI arayüz + kopyalama motoru) |
| `Info.plist` | Uygulama paketi bilgileri |
| `build.sh` | `swiftc` ile derleme ve imzalama betiği |
| `.github/workflows/build.yml` | CI derleme ve release |
| `Kopya.icns` | Uygulama ikonu |
| `tools/make_icon.py` | İkonu yeniden üreten betik (`python3 tools/make_icon.py .`, Pillow + numpy gerekir) |
