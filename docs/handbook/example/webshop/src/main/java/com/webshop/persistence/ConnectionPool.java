package com.webshop.persistence;

class ConnectionPool {

    private int open;

    void acquire() {
        open++;
    }
}
