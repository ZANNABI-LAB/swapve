/// <reference types="vitest" />
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";

// 산출물은 **HTML 한 장**이어야 한다. 콘솔은 네트워크 없는 곳에서도 떠야 하고
// (sim-console/build.gradle.kts 의 checkNoForbiddenDependencies 가 그것을 훑는다),
// 서버는 클래스패스의 /console/index.html 한 파일만 서빙한다 — SimConsoleServer.kt.
// viteSingleFile 이 JS·CSS 를 전부 그 한 장 안으로 인라인한다.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  build: {
    outDir: "../build/ui",
    emptyOutDir: true,
    // 남는 자산이 없어야 한 장이 된다.
    assetsInlineLimit: 100_000_000,
    cssCodeSplit: false,
    // 배포물에 소스맵을 싣지 않는다 — 한 장 원칙과 크기 둘 다의 문제다.
    sourcemap: false,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: "./src/test-setup.ts",
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
