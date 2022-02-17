#!/bin/bash
for filename in res/drawable*/*; do
    file=$(basename ${filename%.*})
    echo $file
    res=$(ack -hc ${file%.*})
    if [ "$res" == "0" ]; then
        rm $filename
    fi
done

