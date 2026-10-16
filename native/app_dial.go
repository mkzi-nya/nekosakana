// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"errors"
	"io"
	"log"
	"net/netip"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
)

// TCPConn is a TCP connection carried by the application's Tailscale netstack.
type TCPConn struct {
	conn *gonet.TCPConn
	readTimeout atomic.Int64
}

func (c *TCPConn) SetReadTimeout(milliseconds int64) {
	c.readTimeout.Store(milliseconds)
}

// ReadBytes returns received bytes across gomobile's copying JNI boundary.
// An input byte slice is not copied back to the Java caller after Read.
func (c *TCPConn) ReadBytes(size int) ([]byte, error) {
	if size <= 0 || size > 1024*1024 {
		return nil, errors.New("invalid read size")
	}
	p := make([]byte, size)
	if timeout := c.readTimeout.Load(); timeout > 0 {
		_ = c.conn.SetReadDeadline(time.Now().Add(time.Duration(timeout) * time.Millisecond))
	} else {
		_ = c.conn.SetReadDeadline(time.Time{})
	}
	n, err := c.conn.Read(p)
	if n > 0 {
		return p[:n], nil
	}
	if errors.Is(err, io.EOF) {
		return nil, nil
	}
	return nil, err
}

// Read copies received bytes into p. It returns -1 after the peer closes the connection.
func (c *TCPConn) Read(p []byte) int {
	n, err := c.conn.Read(p)
	if errors.Is(err, io.EOF) {
		return -1
	}
	if err != nil {
		log.Printf("app netstack read: %v", err)
		return -1
	}
	return n
}

// Write sends all bytes in p or returns -1 on error.
func (c *TCPConn) Write(p []byte) int {
	total := 0
	for total < len(p) {
		n, err := c.conn.Write(p[total:])
		if err != nil || n == 0 {
			if err != nil {
				log.Printf("app netstack write: %v", err)
			}
			return -1
		}
		total += n
	}
	return total
}

// Close closes the TCP connection.
func (c *TCPConn) Close() {
	_ = c.conn.Close()
}

// TCPListener accepts TCP connections delivered by the application's Tailscale netstack.
type TCPListener struct {
	listener *gonet.TCPListener
}

func (l *TCPListener) Address() string {
	return l.listener.Addr().String()
}

// Accept waits for and returns the next connection.
func (l *TCPListener) Accept() (*TCPConn, error) {
	conn, err := l.listener.Accept()
	if err != nil {
		return nil, err
	}
	tcpConn, ok := conn.(*gonet.TCPConn)
	if !ok {
		_ = conn.Close()
		return nil, errors.New("unexpected netstack connection type")
	}
	return &TCPConn{conn: tcpConn}, nil
}

// Close stops accepting connections.
func (l *TCPListener) Close() {
	_ = l.listener.Close()
}

// DialTCP opens a TCP connection over Tailscale without creating an Android VpnService.
func (a *App) DialTCP(address string) (*TCPConn, error) {
	a.ready.Wait()
	a.backendMu.Lock()
	ns := a.netstack
	a.backendMu.Unlock()
	if ns == nil {
		return nil, errors.New("Tailscale netstack is not ready")
	}
	remote, err := netip.ParseAddrPort(address)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn, err := ns.DialContextTCP(ctx, remote)
	if err != nil {
		return nil, err
	}
	return &TCPConn{conn: conn}, nil
}

// ListenTCP listens on a local Tailscale address, without exposing a system-wide VPN route.
func (a *App) ListenTCP(address string) (*TCPListener, error) {
	a.ready.Wait()
	a.backendMu.Lock()
	ns := a.netstack
	a.backendMu.Unlock()
	if ns == nil {
		return nil, errors.New("Tailscale netstack is not ready")
	}
	remote, err := netip.ParseAddrPort(address)
	if err != nil {
		return nil, err
	}
	network := "tcp6"
	if remote.Addr().Is4() {
		network = "tcp4"
	}
	listener, err := ns.ListenTCP(network, address)
	if err != nil {
		return nil, err
	}
	return &TCPListener{listener: listener}, nil
}
