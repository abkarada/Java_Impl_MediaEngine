package media_engine.media_engine;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Bu arayüz, JNA (Java Native Access) kullanarak libsrt (C kütüphanesi)
 * içerisindeki fonksiyonlara Java'dan erişim sağlar.
 */
public interface SRTLibrary extends Library {
    // Sistemde yüklü olan srt kütüphanesini bulur ve yükler.
    // Linux için libsrt.so, Windows için srt.dll, macOS için libsrt.dylib
    SRTLibrary INSTANCE = Native.load("srt", SRTLibrary.class);

    // --- libsrt C Fonksiyon Bildirimleri ---

    /**
     * SRT kütüphanesini başlatır. Uygulama başında bir kez çağrılmalıdır.
     * @return 0 başarılı, -1 hata durumunda.
     */
    int srt_startup();

    /**
     * SRT kütüphanesi tarafından kullanılan kaynakları temizler. Uygulama sonunda bir kez çağrılmalıdır.
     * @return 0 her zaman.
     */
    int srt_cleanup();

    /**
     * Yeni bir SRT soketi oluşturur.
     * @return Geçerli bir soket tanımlayıcısı (socket descriptor) veya hata durumunda -1.
     */
    int srt_create_socket();

    /**
     * Uzak bir SRT sunucusuna bağlanır.
     * @param sock Bağlanacak soket.
     * @param addr Hedef sunucunun sockaddr yapısını gösteren bir pointer.
     * @param addrlen addr yapısının boyutu.
     * @return 0 başarılı, -1 hata durumunda.
     */
    int srt_connect(int sock, Pointer addr, int addrlen);

    /**
     * Belirtilen SRT soketini kapatır.
     * @param sock Kapatılacak soket.
     * @return 0 başarılı, -1 hata durumunda.
     */
    int srt_close(int sock);

    /**
     * SRT bağlantısının anlık istatistiklerini JSON formatında alır.
     * @param sock İstatistikleri alınacak soket.
     * @param json İstatistiklerin yazılacağı byte dizisi (buffer).
     * @param len Buffer'ın boyutu.
     * @param clear 1 ise istatistikler okunduktan sonra sıfırlanır, 0 ise sıfırlanmaz.
     * @return Yazılan byte sayısı, -1 hata durumunda.
     */
    int srt_bistats_json(int sock, byte[] json, int len, int clear);
}