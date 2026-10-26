#!/system/bin/sh
RT=/data/local/tmp/dshp-runtime
echo "TAR2 START $(date)" > $RT/tar2.log
cd $RT/rootfs || { echo "CD_FAIL" >> $RT/tar2.log; exit 1; }
tar -cf - . 2>$RT/tar2err.txt | gzip -1 > $RT/rootfs2.tar.gz
rc=$?
echo "TAR2 DONE rc=$rc $(date)" >> $RT/tar2.log
sync
