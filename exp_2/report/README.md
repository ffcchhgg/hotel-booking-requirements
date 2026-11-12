# 报告与源文件

`report_v2.pdf` 为当前报告；`report_v2.tex` 为用户修订后的LaTeX，保留宋体正文、21张彩色界面局部图和9张彩色UML。最后的仓库说明指向一个固定设计版本；详细材料索引放在[仓库首页](../../README.md)。

在此目录用XeLaTeX编译两次即可生成PDF。依赖图片位于 `assets_color/` 和 `../deliverables/UML_color/`，不要单独移动tex文件。需要宋体（SimSun）、Times New Roman、Arial和Consolas。

```sh
xelatex -no-shell-escape -interaction=nonstopmode -halt-on-error report_v2.tex
xelatex -no-shell-escape -interaction=nonstopmode -halt-on-error report_v2.tex
```

截图照片来源见 [SOURCES.md](../prototype/assets/SOURCES.md)。课程原始资料和完整本地历史不作为报告编译依赖。
