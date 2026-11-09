/* =========================================================
   书阁 · 内置书源
   规则语法（自研，兼容「阅读」书源的 CSS 系写法）
     @css:选择器@attr      attr: text / html / href / src / title / 任意属性
     选择器支持 :eq(n)     .list > li   等标准 CSS
     ##正则##替换          文本清洗，可叠加
     ||                    多个规则取第一个非空
   搜索地址模板：{{key}}  {{page}}
   ========================================================= */
var BUILTIN_SOURCES = [
  {
    key: 'biquge365',
    name: '笔趣阁 365',
    url: 'https://www.biquge365.net',
    group: '内置',
    charset: 'utf-8',
    enabled: true,
    search: {
      method: 'POST',
      url: '/s.php',
      body: 's={{key}}&type=articlename',
      list: '@css:ul.search > li',
      exclude: '.fen'
    },
    searchRules: {
      name: '@css:.name a@text',
      author: '@css:.zuo a@text',
      bookUrl: '@css:.name a@href',
      kind: '@css:.lei a@text',
      lastChapter: '@css:.jie a@text'
    },
    detailRules: {
      name: '@css:h1@text',
      author: '@css:.xinxi .x1 a@text',
      cover: '@css:.zhutu img@src',
      intro: '@css:.x3@text',
      tocUrl: '@css:.gongneng a[href*="/newbook/"]@href'
    },
    tocRules: {
      list: '@css:ul.info > li',
      name: '@css:a@text',
      url: '@css:a@href'
    },
    contentRules: {
      content: '@css:#txt@html',
      title: '@css:#neirong h1@text',
      remove: '.gongneng1, .baocuo, .fanye1, script, style'
    }
  },
  {
    key: 'biqugese',
    name: '笔趣阁 SE',
    url: 'https://www.biqugese.com',
    group: '内置',
    charset: 'utf-8',
    enabled: true,
    search: {
      method: 'GET',
      url: '/search/result.html?searchkey={{key}}',
      list: '@css:ul.librarylist > li'
    },
    searchRules: {
      name: '@css:a.novelname@text',
      author: '@css:p.info span:eq(1) a@text',
      bookUrl: '@css:a.novelname@href',
      cover: '@css:.pt-ll-l img@src',
      kind: '@css:p.info span:eq(2)@text'
    },
    detailRules: {
      name: '@css:.header.line h1@text',
      author: '@css:.novelinfo-l li:eq(0) a@text',
      cover: '@css:.novelinfo-r img@src',
      intro: '',
      tocUrl: ''
    },
    tocRules: {
      list: '@css:ul.dirlist > li',
      name: '@css:a@text',
      url: '@css:a@href'
    },
    contentRules: {
      content: '@css:#chaptercontent@html',
      title: '@css:.title h1@text',
      remove: '#center_tip, script, style, .readpage .info'
    }
  }
];
