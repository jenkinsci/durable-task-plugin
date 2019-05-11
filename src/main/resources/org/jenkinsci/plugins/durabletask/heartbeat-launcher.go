package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"sync"
	"syscall"

	"golang.org/x/sys/unix"
)

func checkHeartbeatErr(err error) {
	checkErr("heartbeat", err)
}

func checkLauncherErr(err error) {
	checkErr("launcher", err)
}

func checkErr(process string, err error) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "(%v) check err: %v\n", process, err.Error())
	}
}

// In the event the launch script is being intentionally terminated, this signal catcher routine
// ensures that the main program will stay alive and record the exit code of the script.
// Without it, there are possible race conditions in which the main program will terminate before
// the script, and thus not record the exit code to the result file
func signalCatcher(sigChan chan os.Signal, launchPgid int, heartbeatPgid int) {
	for sig := range sigChan {
		fmt.Printf("(sig catcher) caught: %v\n", sig)
		switch sig {
		case unix.SIGTERM:
			// TODO: caught sigterm, forward as sigint?
			fmt.Printf("sending sigterm to script process GROUP  %v\n", launchPgid)
			unix.Kill(launchPgid, unix.SIGTERM) // note the minus sign
			fmt.Printf("sending sigterm to heartbeat process GROUP %v\n", heartbeatPgid)
			unix.Kill(heartbeatPgid, unix.SIGTERM)
		}
	}
}

func launcher(wg *sync.WaitGroup, pidChan chan int, pgidChan chan int,
	scriptString string, logPath string, resultPath string, outputPath string) {

	defer wg.Done()
	recordExit := fmt.Sprintf("; echo $? > %v.tmp; mv %v.tmp %v; wait", resultPath, resultPath, resultPath)
	scriptWithExit := scriptString + recordExit
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
	pidChan <- pid
	pgid, err := syscall.Getpgid(pid)
	checkLauncherErr(err)
	pgidChan <- pgid
	fmt.Printf("(launcher) my pid (%v), launched pid (%v), pgid (%v)\n", os.Getpid(), pid, pgid)
	err = scriptCmd.Wait()
	checkLauncherErr(err)
	resultVal := scriptCmd.ProcessState.ExitCode()
	fmt.Printf("(launcher)(%v) script exit code: %v\n", pid, resultVal)
	_, err = os.Stat(resultPath)
	// check if script was terminated before it could write result file
	if os.IsNotExist(err) {
		fmt.Println("(launcher) script terminated before result recorded, creating result file now")
		resultFile, err := os.Create(resultPath)
		checkLauncherErr(err)
		defer resultFile.Close()
		resultFile.WriteString(strconv.Itoa(resultVal))
		checkLauncherErr(err)
		err = resultFile.Close()
		checkLauncherErr(err)
	}
	fmt.Println("(launcher) done")
}

func heartbeat(wg *sync.WaitGroup, pgidChan chan int, launchedPid int,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()
	const HBSCRIPT string = "heartbeat.sh"
	fmt.Printf("(heartbeat) checking if %v is alive\n", launchedPid)
	_, err := os.Stat(controlDir)
	if os.IsNotExist(err) {
		fmt.Fprintf(os.Stderr, "%v\n", err.Error())
		return
	}
	_, err = os.Stat(resultPath)
	if !os.IsNotExist(err) {
		fmt.Printf("Result file already exists, stopping heartbeat.\n%v\n", resultPath)
		return
	}
	// create the heartbeat script
	heartbeat := fmt.Sprintf("pid=\"$$\"; while true ; do kill -0 %v; status=\"$?\"; if [[ \"($status)\" -ne 0 ]]; then break; fi; echo \"(heartbeat)(\"$pid\") found %v\"; touch %v; sleep 3; done; echo \"(\\\"$pid\\\") exiting\"",
		launchedPid, launchedPid, logPath)
	heartbeatPath := controlDir + HBSCRIPT
	heartbeatScript, err := os.Create(heartbeatPath)
	checkHeartbeatErr(err)
	heartbeatScript.WriteString(heartbeat)
	heartbeatScript.Close()

	heartbeatCmd := exec.Command("/bin/sh", heartbeatPath)
	heartbeatCmd.Stdout = os.Stdout
	heartbeatCmd.Stderr = os.Stderr
	heartbeatCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	heartbeatCmd.Start()
	pid := heartbeatCmd.Process.Pid
	pgid, err := syscall.Getpgid(pid)
	checkLauncherErr(err)
	pgidChan <- pgid
	fmt.Printf("(heartbeat) my pid (%v), heartbeat pid (%v), pgid (%v)\n", os.Getpid(), pid, pgid)
	err = heartbeatCmd.Wait()
	fmt.Printf("(heartbeat)(%v) exit", pid)
	checkHeartbeatErr(err)
}

func main() {
	fmt.Printf("number of args: %v\n", os.Args)
	for i := 0; i < len(os.Args); i++ {
		fmt.Printf("arg %v: %v\n", i, os.Args[i])
	}
	argLen := len(os.Args)
	if argLen < 5 || argLen > 6 {
		// invalid number of args
		os.Stderr.WriteString("Input error: expected number of args is 5 or 6 (controlDir, resultPath, logPath, scriptString, outputPath[opt])")
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

	scriptChan := make(chan int)
	scriptGroupChan := make(chan int)
	hbGroupChan := make(chan int)
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, unix.SIGINT, unix.SIGTERM)

	var wg sync.WaitGroup
	wg.Add(2)
	go launcher(&wg, scriptChan, scriptGroupChan, scriptString, logPath, resultPath, outputPath)
	launchedPid := <-scriptChan
	launchedPgid := <-scriptGroupChan
	go heartbeat(&wg, hbGroupChan, launchedPid, controlDir, resultPath, logPath)
	heartbeatPgid := <-hbGroupChan
	go signalCatcher(sigChan, launchedPgid, heartbeatPgid)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	fmt.Println("(main) done.")
}
