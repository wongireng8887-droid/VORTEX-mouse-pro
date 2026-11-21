package com.example.keymapper;

interface IInject {
    void destroy() = 16777114;
    boolean touch(int action, int id, float x, float y) = 1;
    void releaseAll() = 2;
    String lastError() = 3;
}
