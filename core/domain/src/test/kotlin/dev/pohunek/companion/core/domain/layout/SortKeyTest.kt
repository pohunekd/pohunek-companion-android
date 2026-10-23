package dev.pohunek.companion.core.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortKeyTest {
    // Expected values were produced by the desktop companion's `sort_key::between` (pohunek-companion 0b9d150).
    @Test
    fun `between matches the desktop implementation`() {
        val vectors = listOf(
            Triple(null, null, "n"),
            Triple("n", null, "o"),
            Triple("z", null, "zb"),
            Triple("zz", null, "zzb"),
            Triple("zy", null, "zz"),
            Triple("nz", null, "o"),
            Triple(null, "n", "g"),
            Triple(null, "b", "ab"),
            Triple(null, "c", "b"),
            Triple(null, "ab", "aab"),
            Triple(null, "an", "ag"),
            Triple("a", "c", "b"),
            Triple("n", "p", "o"),
            Triple("n", "o", "nb"),
            Triple("n", "no", "nh"),
            Triple("no", "o", "np"),
            Triple("n", "nb", "nab"),
            Triple("nb", "o", "nc"),
            Triple("b", "c", "bb"),
            Triple("z", "zb", "zab"),
            Triple("y", "z", "yb"),
            Triple("yz", "z", "yzb"),
            Triple("p", "n", "q"),
            Triple("n", "n", "o"),
            Triple("g", "n", "j"),
            Triple("n", "t", "q"),
        )
        for ((lower, upper, expected) in vectors) {
            assertEquals("between($lower, $upper)", expected, SortKey.between(lower, upper))
        }
    }

    private fun run(start: String, steps: Int, next: (String) -> String): List<String> =
        generateSequence(start, next).take(steps + 1).toList()

    @Test
    fun `runs of appends prepends and squeezes match the desktop implementation`() {
        val appends = run(SortKey.between(null, null), 60) { SortKey.between(it, null) }
        assertEquals(
            "n,o,p,q,r,s,t,u,v,w,x,y,z,zb,zc,zd,ze,zf,zg,zh,zi,zj,zk,zl,zm,zn,zo,zp,zq,zr,zs,zt,zu,zv,zw,zx,zy,zz," +
                "zzb,zzc,zzd,zze,zzf,zzg,zzh,zzi,zzj,zzk,zzl,zzm,zzn,zzo,zzp,zzq,zzr,zzs,zzt,zzu,zzv,zzw,zzx",
            appends.joinToString(","),
        )
        val prepends = run("n", 6) { SortKey.between(null, it) }
        assertEquals("n,g,d,b,ab,aab,aaab", prepends.joinToString(","))
        val down = run("o", 5) { SortKey.between("n", it) }.drop(1)
        assertEquals("nb,nab,naab,naaab,naaaab", down.joinToString(","))
        val up = run("n", 5) { SortKey.between(it, "o") }.drop(1)
        assertEquals("nb,nc,nd,ne,nf", up.joinToString(","))
    }

    @Test
    fun `repeated inserts between the same neighbours stay strictly ordered`() {
        val lower = "n"
        val upper = "o"
        var belowNewest = upper
        var aboveNewest = lower
        val below = mutableListOf<String>()
        val above = mutableListOf<String>()
        repeat(1000) {
            val down = SortKey.between(lower, belowNewest)
            assertTrue(down, SortKey.isValid(down) && lower < down && down < belowNewest)
            belowNewest = down
            below += down
            val up = SortKey.between(aboveNewest, upper)
            assertTrue(up, SortKey.isValid(up) && aboveNewest < up && up < upper)
            aboveNewest = up
            above += up
        }
        assertEquals(below.size, below.toSet().size)
        assertTrue(below.zipWithNext().all { (a, b) -> a > b })
        assertTrue(above.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `open ends extend in both directions with short keys`() {
        var last = SortKey.between(null, null)
        repeat(200) {
            val next = SortKey.between(last, null)
            assertTrue(SortKey.isValid(next) && next > last)
            last = next
        }
        assertTrue(last, last.length <= 10)
        var first = SortKey.between(null, null)
        repeat(200) {
            val previous = SortKey.between(null, first)
            assertTrue(SortKey.isValid(previous) && previous < first)
            first = previous
        }
    }

    @Test
    fun `a key is valid when lowercase non-empty and not ending in a`() {
        listOf("n", "nb", "z", "zz").forEach { assertTrue(it, SortKey.isValid(it)) }
        listOf("", "a", "na", "N", "n1").forEach { assertFalse(it, SortKey.isValid(it)) }
    }
}
