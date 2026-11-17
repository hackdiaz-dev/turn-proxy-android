// Command tether пересылает трафик точки доступа в VPN без root.
//
// Сервис Shizuku (права shell) создаёт тестовый TUN, назначает его выходом для раздачи и
// передаёт дескриптор сюда через абстрактный Unix-сокет. Здесь трафик из TUN разбирается
// userspace-стеком tun2socks и уходит в локальный SOCKS5 приложения, а тот - через VPN.
// Сокет держится открытым всю сессию: когда сервис его закрывает (или умирает), helper выходит.
package main

import (
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"syscall"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

func main() {
	socket := flag.String("socket", "", "имя абстрактного Unix-сокета сервиса")
	proxy := flag.String("proxy", "", "SOCKS5 приложения, например socks5://127.0.0.1:1080")
	mtu := flag.Int("mtu", 1500, "MTU тестового TUN")
	flag.Parse()
	if *socket == "" || *proxy == "" {
		log.Fatal("tether: нужны -socket и -proxy")
	}

	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: "@" + *socket, Net: "unix"})
	if err != nil {
		log.Fatalf("tether: сокет сервиса: %v", err)
	}
	fd, err := receiveFD(conn)
	if err != nil {
		log.Fatalf("tether: дескриптор TUN: %v", err)
	}

	engine.Insert(&engine.Key{
		Device: fmt.Sprintf("fd://%d", fd),
		Proxy:  *proxy,
		MTU:    *mtu,
		// Только предупреждения и ошибки: info пишет строку на каждое соединение клиента.
		LogLevel: "warn",
	})
	engine.Start()

	// Ждём закрытия сокета сервисом - это сигнал остановки.
	buf := make([]byte, 1)
	for {
		if _, err := conn.Read(buf); err != nil {
			break
		}
	}
	engine.Stop()
	os.Exit(0)
}

// receiveFD читает один дескриптор, переданный через SCM_RIGHTS
// (LocalSocket.setFileDescriptorsForSend на стороне Java).
func receiveFD(conn *net.UnixConn) (int, error) {
	buf := make([]byte, 16)
	oob := make([]byte, syscall.CmsgSpace(4))
	_, oobn, _, _, err := conn.ReadMsgUnix(buf, oob)
	if err != nil {
		return -1, err
	}
	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return -1, err
	}
	for _, m := range msgs {
		fds, err := syscall.ParseUnixRights(&m)
		if err == nil && len(fds) > 0 {
			return fds[0], nil
		}
	}
	return -1, errors.New("в сообщении нет дескриптора")
}
