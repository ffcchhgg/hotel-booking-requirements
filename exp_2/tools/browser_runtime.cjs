// Use the project dependency; an existing browser can be selected explicitly.
const {chromium,expect}=require('playwright/test');
const launchOptions={headless:true};
if(process.env.PLAYWRIGHT_EXECUTABLE_PATH){
 launchOptions.executablePath=process.env.PLAYWRIGHT_EXECUTABLE_PATH;
}
module.exports={chromium,expect,launchOptions};
