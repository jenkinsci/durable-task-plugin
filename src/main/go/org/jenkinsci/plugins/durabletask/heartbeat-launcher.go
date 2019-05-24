package main

import (
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

// Note: EXIT signal is needed as some shell variants require an EXIT trap after catching a signal
const trapSig = "trap ':' INT TERM EXIT"
const base = "main"
const hb = "heartbeat"

func checkHeartbeatErr(output io.Writer, err error) {
	checkErr("heartbeat", err)
}

func checkLauncherErr(err error) {
	checkErr("launcher", err)
}

func checkErr(process string, err error) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "(%v) check err: %v\n", strings.ToUpper(process), err.Error())
	}
}

func logCheckErr(logger *log.Logger, err error) {
	if err != nil {
		logger.Printf("check err: %v\n", err.Error())
	}
}

// Catch a termination signal to allow for a graceful exit (i.e. no zombies)
func signalCatcher(sigChan chan os.Signal) {
	for sig := range sigChan {
		fmt.Printf("(sig catcher) caught: %v\n", sig)
	}
}

func launcher(wg *sync.WaitGroup, exitChan chan bool,
	scriptString string, logPath string, resultPath string, outputPath string) {

	defer wg.Done()
	recordExit := fmt.Sprintf("status=\"$?\"; echo \"$status\" > %v.tmp; mv %v.tmp %v; wait; exit \"$status\"",
		resultPath, resultPath, resultPath)
	scriptWithExit := trapSig + "; " + scriptString + "; " + recordExit
	scriptCmd := exec.Command("/bin/sh", "-c", scriptWithExit)
	logFile, err := os.Create(logPath)
	checkLauncherErr(err)
	defer logFile.Close()

	if outputPath != "" {
		// capturing output
		outputFile, err := os.Create(outputPath)
		checkLauncherErr(err)
		defer outputFile.Close()
		scriptCmd.Stdout = outputFile
	} else {
		scriptCmd.Stdout = logFile
	}
	if outputPath != "" {
		// capturing output
		scriptCmd.Stderr = logFile
	} else {
		// Note: pointing to os.Stdout will not capture all err logs and fail unit tests
		scriptCmd.Stderr = scriptCmd.Stdout
	}
	// Prevents script from being terminated if program gets terminated
	scriptCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	for i := 0; i < len(scriptCmd.Args); i++ {
		fmt.Printf("(launcher) args %v: %v\n", i, scriptCmd.Args[i])
	}
	scriptCmd.Start()
	pid := scriptCmd.Process.Pid
	// pidChan <- pid
	fmt.Printf("(launcher) launched %v\n", pid)
	err = scriptCmd.Wait()
	checkLauncherErr(err)
	resultVal := scriptCmd.ProcessState.ExitCode()
	fmt.Printf("(launcher) script exit code: %v\n", resultVal)
	exitChan <- true
}

func heartbeat(wg *sync.WaitGroup, exitChan chan bool,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()
	debugFile, debugErr := os.Create(controlDir + hb + ".log")
	checkErr(hb, debugErr)
	defer debugFile.Close()
	debugPrefix := strings.ToUpper(hb) + " "
	logger := log.New(debugFile, debugPrefix, log.Ltime|log.Lshortfile)

	// const HBSCRIPT string = "heartbeat.sh"
	// fmt.Printf("(heartbeat) checking if %v is alive\n", launchedPid)
	_, err := os.Stat(controlDir)
	if os.IsNotExist(err) {
		logger.Printf("%v\n", err.Error())
		return
	}
	_, err = os.Stat(resultPath)
	if !os.IsNotExist(err) {
		logger.Printf("Result file already exists, stopping heartbeat.\n%v\n", resultPath)
		return
	}
	// create the heartbeat script
	// heartbeat := fmt.Sprintf("#! /bin/sh\n%v; pid=\"$$\"; echo \"(heartbeat) pid: \"$pid\"\"; while true ; do kill -0 %v; status=\"$?\"; if [ \"$status\" -ne 0 ]; then break; fi; echo \"\"$pid\"\"; touch %v; sleep 3; done; echo \"(\\\"$pid\\\") exiting\"; exit",
	// 	trapSig, launchedPid, logPath)
	// heartbeatPath := controlDir + HBSCRIPT
	// heartbeatScript, err := os.Create(heartbeatPath)
	// checkHeartbeatErr(err)
	// err = os.Chmod(heartbeatPath, 0755)
	// checkHeartbeatErr(err)
	// heartbeatScript.WriteString(heartbeat)
	// heartbeatScript.Close()

	// heartbeatCmd := exec.Command(heartbeatPath)

	/************************************
	// Warning: DO NOT set cmd.Stdout/StdErr is set to os.Stdout/Stderr
	// If you do, the heartbeat thread will not survive jenkins termination
	///////////// DO NOT DO THIS ///////////////
	// heartbeatCmd.Stdout = os.Stdout
	// heartbeatCmd.Stderr = os.Stderr
	************************************************/
	// heartbeatCmd.Stdout = logFile
	// heartbeatCmd.Stderr = logFile
	// heartbeatCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	// heartbeatCmd.Start()
	// pid := heartbeatCmd.Process.Pid
	// fmt.Printf("(heartbeat) heartbeat pid (%v)\n", pid)
	// err = heartbeatCmd.Wait()
	// checkHeartbeatErr(err)

	for {
		select {
		case <-exitChan:
			logger.Println("received script finished, exiting")
			return
		default:
			// heartbeat
			logger.Println("touch log")
			err = os.Chtimes(logPath, time.Now(), time.Now())
			logCheckErr(logger, err)
			time.Sleep(time.Second * 3)
		}
	}
	// logger.Println("exit")
}

func main() {
	argLen := len(os.Args)
	if argLen < 5 || argLen > 6 {
		// invalid number of args
		// os.Stderr.WriteString("Input error: expected number of args is 5 or 6 (controlDir, resultPath, logPath, scriptString, outputPath[opt])")
		return
	}
	controlDir := os.Args[1]
	resultPath := os.Args[2]
	logPath := os.Args[3]
	scriptString := os.Args[4]
	outputPath := ""
	if argLen == 6 {
		outputPath = os.Args[5]
	}

	debugFile, debugErr := os.Create(controlDir + base + ".log")
	checkErr(base, debugErr)
	defer debugFile.Close()
	debugPrefix := strings.ToUpper(base) + " "
	debugLogger := log.New(debugFile, debugPrefix, log.Ltime|log.Lshortfile)

	debugLogger.Printf("number of args: %v\n", os.Args)
	for i := 0; i < len(os.Args); i++ {
		debugLogger.Printf("arg %v: %v\n", i, os.Args[i])
	}

	ppid := os.Getppid()
	debugLogger.Printf("Parent pid is: %v\n", ppid)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, unix.SIGINT, unix.SIGTERM)
	go signalCatcher(sigChan)

	exitChan := make(chan bool)
	var wg sync.WaitGroup
	wg.Add(2)
	go launcher(&wg, exitChan, scriptString, logPath, resultPath, outputPath)
	go heartbeat(&wg, exitChan, controlDir, resultPath, logPath)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	debugLogger.Println("done.")
}
