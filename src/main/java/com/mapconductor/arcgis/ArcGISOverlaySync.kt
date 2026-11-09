package com.mapconductor.arcgis

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 同期（`add`）を、始めたら最後まで終える。
 *
 * compose は overlay の state が変わるたびに前の同期（LaunchedEffect）を取り消す。レンダラは
 * Graphic や層を自分のスコープ（メインスレッド・Default）で地図に載せ、呼び出し側は compose の
 * ディスパッチャで待っている。載せ終わって戻る前に取り消されると、`withContext` は結果を捨てて
 * CancellationException を投げるので、地図に載ったものが entity に登録されない。次の同期が同じ
 * id をもう一度載せて 2 つになり、残った方はどこからも外されない（clearOverlays でも消えない）。
 * タブレットの 2D で、起動直後にマーカー・ポリゴン・ポリラインが同じ id で 2 つずつ入った。
 *
 * 取り消された同期も最後まで走らせ、載せたものを必ず登録する。取り消しの理由だった新しい
 * state は、次の同期（semaphore の後ろで待っている）がそのまま反映する。
 */
internal suspend fun <T> finishEvenIfCancelled(block: suspend () -> T): T = withContext(NonCancellable) { block() }
