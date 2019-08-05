/*
 * The MIT License
 *
 * Copyright 2019 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package main

import (
	"flag"
	"fmt"
	"io/ioutil"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

var mainLogger *log.Logger
var hbLogger *log.Logger
var launchLogger *log.Logger
var scriptLogger *log.Logger

func checkIfErr(process string, err error) bool {
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v: %v\n", strings.ToUpper(process), err.Error())
		return true
	}
	return false
}

func checkScriptErr(err error) bool {
	return loggerIfErr(scriptLogger, err)
}

func loggerIfErr(logger *log.Logger, err error) bool {
	if err != nil {
		logger.Println(err.Error())
		return true
	}
	return false
}

// Catch termination signals to allow for a graceful exit (i.e. no zombies)
// Only for this process, does not catch any signals to the launched script.
func signalCatcher(sigChan chan os.Signal) {
	for sig := range sigChan {
		mainLogger.Printf("(sig catcher) caught: %v\n", sig)
	}
}

// Launches the script in a new session and waits for its completion.
func launcher(wg *sync.WaitGroup, exitChan chan bool, cookieName string, cookieVal string,
	interpreter string, scriptPath string, resultPath string, outputPath string) {

	defer wg.Done()
	defer signalFinished(exitChan)

	var scriptCmd *exec.Cmd
	if interpreter != "" {
		scriptCmd = exec.Command(interpreter, "-xe", scriptPath)
	} else {
		scriptCmd = exec.Command(scriptPath)
	}
	cookieString := fmt.Sprintf("%v=%v", cookieName, cookieVal)
	scriptCmd.Env = append(os.Environ(),
		cookieString)

	if outputPath != "" {
		// capturing output
		outputFile, err := os.Create(outputPath)
		if checkScriptErr(err) {
			exitLauncher(-2, resultPath)
			return
		}
		defer outputFile.Close()
		scriptCmd.Stdout = outputFile
		scriptCmd.Stderr = scriptLogger.Writer()
	} else {
		scriptCmd.Stdout = scriptLogger.Writer()
		scriptCmd.Stderr = scriptCmd.Stdout
	}
	// Create new session
	scriptCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	for i := 0; i < len(scriptCmd.Args); i++ {
		launchLogger.Printf("args %v: %v\n", i, scriptCmd.Args[i])
	}
	err := scriptCmd.Start()
	if checkScriptErr(err) {
		exitLauncher(-2, resultPath)
		return
	}
	pid := scriptCmd.Process.Pid
	launchLogger.Printf("launched %v\n", pid)
	err = scriptCmd.Wait()
	checkScriptErr(err)
	resultVal := scriptCmd.ProcessState.ExitCode()
	launchLogger.Printf("script exit code: %v\n", resultVal)

	exitLauncher(resultVal, resultPath)
}

func signalFinished(exitChan chan bool) {
	exitChan <- true
}

func exitLauncher(exitCode int, resultPath string) {
	resultFile, err := os.Create(resultPath)
	if checkScriptErr(err) {
		return
	}
	defer resultFile.Close()
	_, err = resultFile.WriteString(strconv.Itoa(exitCode))
	checkScriptErr(err)
	err = resultFile.Close()
	checkScriptErr(err)
	launchLogger.Println("done")
}

// Touches log file while launched script is still active
func heartbeat(wg *sync.WaitGroup, exitChan chan bool,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()

	_, err := os.Stat(controlDir)
	if os.IsNotExist(err) {
		hbLogger.Printf("%v\n", err.Error())
		return
	}
	_, err = os.Stat(resultPath)
	if !os.IsNotExist(err) {
		hbLogger.Printf("Result file already exists, stopping heartbeat.\n%v\n", resultPath)
		return
	}

	for {
		select {
		case <-exitChan:
			hbLogger.Println("received script finished, exiting")
			return
		default:
			// heartbeat
			hbLogger.Println("touch log")
			err = os.Chtimes(logPath, time.Now(), time.Now())
			loggerIfErr(hbLogger, err)
			time.Sleep(time.Second * 3)
		}
	}
}

// Launches a script in a new session and monitors its running status. This program should
// survive the termination of its launching process with or without the -daemon flag. No part of this
// program should output to stdout/stderr or else it can terminate when its parent process has terminated.
func main() {
	var controlDir, resultPath, logPath, cookieName, cookieVal, scriptPath, interpreter, outputPath string
	var debug, daemon bool
	const controlFlag = "controldir"
	const resultFlag = "result"
	const logFlag = "log"
	const cookieNameFlag = "cookiename"
	const cookieValFlag = "cookieval"
	const scriptFlag = "script"
	const shellFlag = "shell"
	const outputFlag = "output"
	const debugFlag = "debug"
	const daemonFlag = "daemon"
	flag.StringVar(&controlDir, controlFlag, "", "working directory")
	flag.StringVar(&resultPath, resultFlag, "", "full path of the result file")
	flag.StringVar(&logPath, logFlag, "", "full path of the log file")
	flag.StringVar(&cookieName, cookieNameFlag, "", "name of the jenkins server cookie")
	flag.StringVar(&cookieVal, cookieValFlag, "", "value of the jenkins server cookie")
	flag.StringVar(&scriptPath, scriptFlag, "", "full path of the script to be launched")
	flag.StringVar(&interpreter, shellFlag, "", "(optional) interpreter to use")
	flag.StringVar(&outputPath, outputFlag, "", "(optional) if recording output, full path of the output file")
	flag.BoolVar(&debug, debugFlag, false, "noisy output to log")
	flag.BoolVar(&daemon, daemonFlag, false, "Immediately free binary from parent process")
	flag.Parse()

	// Validate that the required flags were all command-line defined
	required := []string{controlFlag, resultFlag, logFlag, cookieNameFlag, cookieValFlag, scriptFlag}
	defined := make(map[string]string)
	flag.Visit(func(f *flag.Flag) {
		defined[f.Name] = f.Value.String()
	})
	var missing []string
	for _, reqFlag := range required {
		if _, exists := defined[reqFlag]; !exists {
			missing = append(missing, reqFlag)
		}
	}
	if len(missing) > 0 {
		fmt.Println("The following required flags are missing:")
		for _, missingFlag := range missing {
			fmt.Printf("-%v\n", missingFlag)
		}
		return
	}

	// Double launch to free from parent process. Using a flag because it is possible for parent PID = 1 (i.e. Docker with no init process)
	if daemon {
		rebuiltArgs := make([]string, len(os.Args[1:]))
		argIndex := 0
		for argKey, argValue := range defined {
			if argKey != daemonFlag {
				rebuiltArgs[argIndex] = fmt.Sprintf("-%v=%v", argKey, argValue)
				argIndex++
			}
		}
		doubleLaunchCmd := exec.Command(os.Args[0], rebuiltArgs...)
		doubleLaunchCmd.Stdout = nil
		doubleLaunchCmd.Stderr = nil
		doubleLaunchCmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
		doubleLaunchErr := doubleLaunchCmd.Start()
		if doubleLaunchErr != nil {
			panic("Double launch failed, exiting")
		}
		return
	}

	// Prepare logging
	logFile, logErr := os.Create(logPath)
	if checkIfErr("launcher", logErr) {
		return
	}
	defer logFile.Close()
	mainLogOut := ioutil.Discard
	hbLogOut := ioutil.Discard
	launchLogOut := ioutil.Discard
	if debug {
		mainLogOut = logFile
		hbLogOut = logFile
		launchLogOut = logFile
	}
	mainLogger = log.New(mainLogOut, "MAIN ", log.Lmicroseconds|log.Lshortfile)
	hbLogger = log.New(hbLogOut, "HEARBEAT ", log.Lmicroseconds|log.Lshortfile)
	launchLogger = log.New(launchLogOut, "LAUNCHER ", log.Lmicroseconds|log.Lshortfile)
	scriptLogger = log.New(logFile, "", log.Lmicroseconds|log.Lshortfile)

	for key, val := range defined {
		mainLogger.Printf("%v: %v", key, val)
	}
	mainLogger.Printf("Main pid is: %v\n", os.Getpid())
	mainLogger.Printf("Parent pid is: %v\n", os.Getppid())

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, unix.SIGINT, unix.SIGTERM, unix.SIGHUP)
	go signalCatcher(sigChan)

	exitChan := make(chan bool)
	var wg sync.WaitGroup
	wg.Add(2)
	go launcher(&wg, exitChan, cookieName, cookieVal, interpreter, scriptPath, resultPath, outputPath)
	go heartbeat(&wg, exitChan, controlDir, resultPath, logPath)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	mainLogger.Println("done.")
}
