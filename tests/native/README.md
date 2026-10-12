# Teste local do motor

`forwarding_test.c` usa apenas sockets loopback. Verifica UDP ida/volta, handshake TCP com payload e resposta, e recusa de socket quando proteção/bind falham. Não usa TUN, não precisa de root e não faz varredura.

Exemplo de compilação no macOS com NDK 27.2 e execução em Android arm64 autorizado por ADB:

```sh
"$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang" -DANDROID -D_LITTLE_ENDIAN -DNO_DEBUG -Iapp/src/main/cpp/zdtun tests/native/forwarding_test.c app/src/main/cpp/zdtun/zdtun.c app/src/main/cpp/zdtun/utils.c -o /tmp/nettool-forwarding-test
adb push /tmp/nettool-forwarding-test /data/local/tmp/nettool-forwarding-test
adb shell chmod 700 /data/local/tmp/nettool-forwarding-test
adb shell /data/local/tmp/nettool-forwarding-test
```

Resultado em 2026-10-12 no S23 do usuário, Android 16 API 36, arm64: três verificações PASS. Este teste prova encaminhamento pelo motor com servidor local. O teste adicional com VPN e HTTPS no APK verifica integração TUN/protect/bind, que este harness isolado não cobre.
